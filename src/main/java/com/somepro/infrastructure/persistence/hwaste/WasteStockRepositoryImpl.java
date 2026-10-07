package com.somepro.infrastructure.persistence.hwaste;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.hwaste.model.CategoryStatus;
import com.somepro.domain.hwaste.model.SourceStatus;
import com.somepro.domain.hwaste.model.SplitItem;
import com.somepro.domain.hwaste.model.StockStatus;
import com.somepro.domain.hwaste.model.WasteStock;
import com.somepro.domain.hwaste.repository.WasteStockRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.persistence.base.BaseBlockingRepository;
import com.somepro.infrastructure.persistence.hwaste.converter.WasteStockPoConverter;
import com.somepro.infrastructure.persistence.hwaste.po.WasteCategoryPO;
import com.somepro.infrastructure.persistence.hwaste.po.WasteSourcePO;
import com.somepro.infrastructure.persistence.hwaste.po.WasteStockPO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 入库批次仓储适配器（基础设施层）。
 *
 * 入库、转出、拆分、合并共用一把 WB 锁：批次号「取号 + 落库」串行，
 * 转出 FIFO 消化与拆并的「读状态 → 改写」整段也串行 —— 两个线程同时拆同一批时，
 * 后到的在锁里重读状态，看到父批已 VOID 就走幂等空操作，不会把一批货拆出两份账。
 * 库表 uk_batch_no 唯一约束是最后兜底。
 */
@Repository
public class WasteStockRepositoryImpl extends BaseBlockingRepository implements WasteStockRepository {

    private final WasteStockMapper wasteStockMapper;
    private final WasteCategoryMapper wasteCategoryMapper;
    private final WasteSourceMapper wasteSourceMapper;
    private final BizNoService bizNoService;
    private final TransactionTemplate txTemplate;

    public WasteStockRepositoryImpl(WasteStockMapper wasteStockMapper, WasteCategoryMapper wasteCategoryMapper,
                                    WasteSourceMapper wasteSourceMapper,
                                    BizNoService bizNoService,
                                    PlatformTransactionManager transactionManager) {
        this.wasteStockMapper = wasteStockMapper;
        this.wasteCategoryMapper = wasteCategoryMapper;
        this.wasteSourceMapper = wasteSourceMapper;
        this.bizNoService = bizNoService;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<WasteStock> inbound(WasteStock stock) {
        return blocking(() -> bizNoService.inLock("WB", () -> txTemplate.execute(tx -> {
            // 先锁产废单位，再锁危废类别，全链路按同一顺序拿锁避免死锁。
            // 锁里看到单位停用就挡回，停用提交后不会再冒出该单位的新入库批次。
            WasteSourcePO source = wasteSourceMapper.selectByIdForUpdate(stock.getSourceId());
            if (source == null) {
                throw new BizException("产废单位不存在");
            }
            if (!SourceStatus.ACTIVE.name().equals(source.getStatus())) {
                throw new BizException("产废单位非正常状态，禁止入库");
            }
            // 锁类别行：与「停用名录」互斥。锁里看到停用就挡回，看到启用才登新入库，
            // 停用之后不会再冒出该类别的新批次。
            WasteCategoryPO category = wasteCategoryMapper.selectByCodeForUpdate(stock.getCategoryCode());
            if (category == null) {
                throw new BizException("危废类别不存在");
            }
            if (!CategoryStatus.ENABLED.name().equals(category.getStatus())) {
                throw new BizException("危废类别已停用，禁止入库");
            }
            WasteStockPO po = WasteStockPoConverter.toPo(stock);
            po.setId(IdUtil.getSnowflakeNextId());
            po.setBatchNo(bizNoService.nextBatchNo());
            wasteStockMapper.insert(po);
            return WasteStockPoConverter.toDomain(po);
        })));
    }

    @Override
    public Mono<BigDecimal> sumInStock(Long sourceId, String categoryCode) {
        return blocking(() -> wasteStockMapper.sumInStockWeight(sourceId, categoryCode));
    }

    @Override
    public Mono<BigDecimal> transferOut(Long sourceId, String categoryCode, BigDecimal weightKg) {
        return blocking(() -> bizNoService.inLock("WB", () -> txTemplate.execute(tx -> {
            List<WasteStockPO> batches = wasteStockMapper.selectList(Wrappers.<WasteStockPO>lambdaQuery()
                    .eq(WasteStockPO::getSourceId, sourceId)
                    .eq(WasteStockPO::getCategoryCode, categoryCode)
                    .eq(WasteStockPO::getStatus, StockStatus.IN_STOCK.name())
                    .orderByAsc(WasteStockPO::getInAt)
                    .orderByAsc(WasteStockPO::getId));
            BigDecimal total = batches.stream()
                    .map(WasteStockPO::getWeightKg)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(weightKg) < 0) {
                throw new BizException("在库重量不足，无法转出");
            }
            BigDecimal remaining = weightKg;
            for (WasteStockPO batch : batches) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal weight = batch.getWeightKg();
                if (weight.compareTo(remaining) <= 0) {
                    // 整批转出
                    remaining = remaining.subtract(weight);
                    WasteStockPO update = new WasteStockPO();
                    update.setId(batch.getId());
                    update.setStatus(StockStatus.TRANSFERRED.name());
                    wasteStockMapper.updateById(update);
                } else {
                    // 部分转出：父批作废，拆出「转出部分」与「留存部分」两个子批，父批原记录保留
                    insertChild(batch, remaining, StockStatus.TRANSFERRED);
                    insertChild(batch, weight.subtract(remaining), StockStatus.IN_STOCK);
                    WasteStockPO update = new WasteStockPO();
                    update.setId(batch.getId());
                    update.setStatus(StockStatus.VOID.name());
                    wasteStockMapper.updateById(update);
                    remaining = BigDecimal.ZERO;
                }
            }
            return weightKg;
        })));
    }

    @Override
    public Mono<List<WasteStock>> split(Long batchId, List<SplitItem> items) {
        return blocking(() -> bizNoService.inLock("WB", () -> txTemplate.execute(tx -> {
            WasteStockPO parent = wasteStockMapper.selectById(batchId);
            if (parent == null) {
                throw new BizException("批次不存在");
            }
            if (StockStatus.VOID.name().equals(parent.getStatus())) {
                // 已拆过 / 并过：幂等，直接回现有子批，不再重复拆
                return listChildren(parent.getId());
            }
            // 状态与合计校验在领域行为里：已转出 / 已处置 / 被联单占住、合计对不上都会挡回
            List<WasteStock> children = WasteStockPoConverter.toDomain(parent).split(items);
            for (WasteStock child : children) {
                WasteStockPO childPo = WasteStockPoConverter.toPo(child);
                childPo.setId(IdUtil.getSnowflakeNextId());
                childPo.setBatchNo(bizNoService.nextBatchNo());
                wasteStockMapper.insert(childPo);
                child.setId(childPo.getId());
                child.setBatchNo(childPo.getBatchNo());
            }
            // 父批退出在库账，原记录保留可追溯
            WasteStockPO update = new WasteStockPO();
            update.setId(parent.getId());
            update.setStatus(StockStatus.VOID.name());
            wasteStockMapper.updateById(update);
            return children;
        })));
    }

    @Override
    public Mono<WasteStock> merge(List<Long> batchIds) {
        return blocking(() -> bizNoService.inLock("WB", () -> txTemplate.execute(tx -> {
            List<Long> ids = batchIds.stream().distinct().collect(Collectors.toList());
            List<WasteStockPO> rows = wasteStockMapper.selectBatchIds(ids);
            if (rows.size() != ids.size()) {
                throw new BizException("批次不存在或已删除");
            }
            // 已并过 / 拆过的批次（VOID）跳过；剩下的在库批次不足两个时没什么可并，幂等空操作
            List<WasteStock> candidates = rows.stream()
                    .filter(po -> !StockStatus.VOID.name().equals(po.getStatus()))
                    .map(WasteStockPoConverter::toDomain)
                    .collect(Collectors.toList());
            if (candidates.size() < 2) {
                return null;
            }
            // 硬杠子在领域行为里：已转出 / 已处置 / 被联单占住、非同单位同类别同包装都会挡回
            WasteStock merged = WasteStock.mergeOf(candidates);
            for (WasteStock candidate : candidates) {
                WasteStockPO update = new WasteStockPO();
                update.setId(candidate.getId());
                update.setStatus(StockStatus.VOID.name());
                wasteStockMapper.updateById(update);
            }
            WasteStockPO mergedPo = WasteStockPoConverter.toPo(merged);
            mergedPo.setId(IdUtil.getSnowflakeNextId());
            mergedPo.setBatchNo(bizNoService.nextBatchNo());
            wasteStockMapper.insert(mergedPo);
            merged.setId(mergedPo.getId());
            merged.setBatchNo(mergedPo.getBatchNo());
            return merged;
        })));
    }

    @Override
    public Mono<PageResult<WasteStock>> page(int pageNum, int pageSize, Long sourceId, String categoryCode,
                                             String packageType, String status,
                                             LocalDate inDateFrom, LocalDate inDateTo) {
        return this.<PageResult<WasteStock>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<WasteStockPO> wrapper = Wrappers.<WasteStockPO>lambdaQuery()
                        .eq(sourceId != null, WasteStockPO::getSourceId, sourceId)
                        .eq(categoryCode != null && !categoryCode.isBlank(),
                                WasteStockPO::getCategoryCode, categoryCode)
                        .eq(packageType != null && !packageType.isBlank(),
                                WasteStockPO::getPackageType, packageType)
                        .eq(status != null && !status.isBlank(), WasteStockPO::getStatus, status)
                        .ge(inDateFrom != null, WasteStockPO::getInAt,
                                inDateFrom == null ? null : inDateFrom.atStartOfDay())
                        .lt(inDateTo != null, WasteStockPO::getInAt,
                                inDateTo == null ? null : inDateTo.plusDays(1).atStartOfDay())
                        .orderByDesc(WasteStockPO::getId);
                List<WasteStockPO> rows = wasteStockMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<WasteStock> content = rows.stream()
                        .map(WasteStockPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    /** 拆分子批：继承父批的单位 / 类别 / 包装 / 入库时刻，parent_batch_id 指回父批。 */
    private void insertChild(WasteStockPO parent, BigDecimal weight, StockStatus status) {
        WasteStockPO child = new WasteStockPO();
        child.setId(IdUtil.getSnowflakeNextId());
        child.setBatchNo(bizNoService.nextBatchNo());
        child.setSourceId(parent.getSourceId());
        child.setCategoryCode(parent.getCategoryCode());
        child.setPackageType(parent.getPackageType());
        child.setWeightKg(weight);
        child.setInAt(parent.getInAt());
        child.setStatus(status.name());
        child.setParentBatchId(parent.getId());
        wasteStockMapper.insert(child);
    }

    /** 某父批拆出的全部子批（幂等重查用）。 */
    private List<WasteStock> listChildren(Long parentId) {
        return wasteStockMapper.selectList(Wrappers.<WasteStockPO>lambdaQuery()
                        .eq(WasteStockPO::getParentBatchId, parentId)
                        .orderByAsc(WasteStockPO::getId))
                .stream()
                .map(WasteStockPoConverter::toDomain)
                .collect(Collectors.toList());
    }
}
