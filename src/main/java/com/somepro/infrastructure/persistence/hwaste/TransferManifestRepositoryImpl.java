package com.somepro.infrastructure.persistence.hwaste;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.hwaste.model.CategoryStatus;
import com.somepro.domain.hwaste.model.ManifestSignoff;
import com.somepro.domain.hwaste.model.ManifestStatus;
import com.somepro.domain.hwaste.model.SourceStatus;
import com.somepro.domain.hwaste.model.StockStatus;
import com.somepro.domain.hwaste.model.TransferManifest;
import com.somepro.domain.hwaste.repository.TransferManifestRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.persistence.base.BaseBlockingRepository;
import com.somepro.infrastructure.persistence.hwaste.converter.TransferManifestPoConverter;
import com.somepro.infrastructure.persistence.hwaste.po.ManifestSignoffPO;
import com.somepro.infrastructure.persistence.hwaste.po.TransferManifestPO;
import com.somepro.infrastructure.persistence.hwaste.po.WasteCategoryPO;
import com.somepro.infrastructure.persistence.hwaste.po.WasteSourcePO;
import com.somepro.infrastructure.persistence.hwaste.po.WasteStockPO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 电子转移联单仓储适配器（基础设施层）。
 *
 * 提交的「额度复核 + 取号 + 插入」包在同一把 EM 锁里：同一计划下两笔几乎同时提交，
 * 后到的在锁里重算已开出量，额度不够就挡回去，不会一起把额度用穿；
 * 同一个联单号也只成一单，库表 uk_manifest_no 唯一约束是最后兜底。
 * 审批 / 退回 / 启运走条件更新（UPDATE ... WHERE id=? AND status=<源状态>），
 * 更新 0 行说明已被别人推过状态，后到的请求直接拒掉。
 * 签收是一个事务：联单条件置签收 → 处置单位累计接收加码（许可余量硬闸）→ 写签收留痕；
 * 并发重复签收时后到的联单更新 0 行，整事务回滚，处置单位不会跟着重复加码。
 * 处置确认也是一个事务：联单条件置已处置 → 签收单补齐处置重量 / 方式 / 确认时刻 →
 * 这趟货占用的在库批次核销成已处置；并发重复确认时后到的更新 0 行，整事务回滚，
 * 同一张联单确认不了两回，批次也不会被重复核销。
 */
@Repository
public class TransferManifestRepositoryImpl extends BaseBlockingRepository implements TransferManifestRepository {

    private final TransferManifestMapper transferManifestMapper;
    private final TreatmentUnitMapper treatmentUnitMapper;
    private final ManifestSignoffMapper manifestSignoffMapper;
    private final WasteStockMapper wasteStockMapper;
    private final WasteCategoryMapper wasteCategoryMapper;
    private final WasteSourceMapper wasteSourceMapper;
    private final BizNoService bizNoService;
    private final TransactionTemplate txTemplate;

    public TransferManifestRepositoryImpl(TransferManifestMapper transferManifestMapper,
                                          TreatmentUnitMapper treatmentUnitMapper,
                                          ManifestSignoffMapper manifestSignoffMapper,
                                          WasteStockMapper wasteStockMapper,
                                          WasteCategoryMapper wasteCategoryMapper,
                                          WasteSourceMapper wasteSourceMapper,
                                          BizNoService bizNoService,
                                          PlatformTransactionManager transactionManager) {
        this.transferManifestMapper = transferManifestMapper;
        this.treatmentUnitMapper = treatmentUnitMapper;
        this.manifestSignoffMapper = manifestSignoffMapper;
        this.wasteStockMapper = wasteStockMapper;
        this.wasteCategoryMapper = wasteCategoryMapper;
        this.wasteSourceMapper = wasteSourceMapper;
        this.bizNoService = bizNoService;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<TransferManifest> create(TransferManifest manifest, BigDecimal approvedWeight) {
        return blocking(() -> bizNoService.inLock("EM", () -> txTemplate.execute(tx -> {
            // 先锁产废单位行、再锁类别行（与新入库同一「单位 → 类别」加锁顺序，避免死锁）：
            // 单位停用与开新联单在这里互斥 —— 锁里看到单位非正常（停用 / 关闭）就挡回，
            // 停用之后名下年度计划开不出新联单；已开出去在途的老联单不看这里，照走签收 / 处置。
            WasteSourcePO source = wasteSourceMapper.selectByIdForUpdate(manifest.getSourceId());
            if (source == null) {
                throw new BizException("产废单位不存在");
            }
            if (!SourceStatus.ACTIVE.name().equals(source.getStatus())) {
                throw new BizException("产废单位非正常状态，不能开具新联单");
            }
            // 锁类别行：与「停用名录」互斥。锁里看到停用就挡回，看到启用就照开，
            // 不会出现名录已停用、却挂着停用之后新开的联单。
            WasteCategoryPO category = wasteCategoryMapper.selectByCodeForUpdate(manifest.getCategoryCode());
            if (category == null) {
                throw new BizException("危废类别不存在");
            }
            if (!CategoryStatus.ENABLED.name().equals(category.getStatus())) {
                throw new BizException("危废类别已停用，不能开具新联单");
            }
            // 跨省标志以锁内名录为准：名录刚改成限制，这张跨省单就挡在外面；
            // 已开出的老联单上的快照值不动，只影响这张新开的。
            boolean cross = manifest.getCrossProvince() != null && manifest.getCrossProvince() == 1;
            boolean restricted = category.getCrossProvince() != null && category.getCrossProvince() == 1;
            if (cross && restricted) {
                throw new BizException("该危废类别限制跨省转移，不得跨省开具联单");
            }
            // 锁内复核额度：已开出量 + 本趟量不得盖过计划批复总量
            BigDecimal used = transferManifestMapper.sumTransferWeight(manifest.getPlanId());
            manifest.requireWithinQuota(approvedWeight, used);
            TransferManifestPO po = TransferManifestPoConverter.toPo(manifest);
            po.setId(IdUtil.getSnowflakeNextId());
            po.setManifestNo(bizNoService.nextManifestNo());
            transferManifestMapper.insert(po);
            return TransferManifestPoConverter.toDomain(po);
        })));
    }

    @Override
    public Mono<TransferManifest> findById(Long id) {
        return blocking(() -> {
            TransferManifestPO po = transferManifestMapper.selectById(id);
            return po == null ? null : TransferManifestPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<TransferManifest> findByManifestNo(String manifestNo) {
        return blocking(() -> {
            TransferManifestPO po = transferManifestMapper.selectOne(Wrappers.<TransferManifestPO>lambdaQuery()
                    .eq(TransferManifestPO::getManifestNo, manifestNo));
            return po == null ? null : TransferManifestPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<TransferManifest> approve(TransferManifest manifest) {
        return blocking(() -> transition(manifest, ManifestStatus.APPROVED.name(),
                "联单已审批或已退回，不能重复审批"));
    }

    @Override
    public Mono<TransferManifest> reject(TransferManifest manifest) {
        return blocking(() -> transition(manifest, ManifestStatus.REJECTED.name(),
                "联单已审批或已退回，不能重复退回"));
    }

    @Override
    public Mono<TransferManifest> depart(TransferManifest manifest) {
        return blocking(() -> {
            TransferManifestPO patch = new TransferManifestPO();
            patch.setStatus(ManifestStatus.IN_TRANSIT.name());
            patch.setTransportBegin(manifest.getTransportBegin());
            int rows = transferManifestMapper.update(patch, Wrappers.<TransferManifestPO>lambdaUpdate()
                    .eq(TransferManifestPO::getId, manifest.getId())
                    .eq(TransferManifestPO::getStatus, ManifestStatus.APPROVED.name()));
            if (rows == 0) {
                throw new BizException("联单不在已审批状态，不能启运");
            }
            return TransferManifestPoConverter.toDomain(transferManifestMapper.selectById(manifest.getId()));
        });
    }

    @Override
    public Mono<TransferManifest> receive(TransferManifest manifest, BigDecimal actualWeight) {
        return blocking(() -> txTemplate.execute(tx -> {
            // 1. 联单条件置签收：只有 IN_TRANSIT 推得动；手快重复签收时后到的更新 0 行，
            //    整事务回滚，处置单位不会跟着重复加码，同一张联单签不了两回
            TransferManifestPO patch = new TransferManifestPO();
            patch.setStatus(ManifestStatus.RECEIVED.name());
            patch.setReceiveAt(manifest.getReceiveAt());
            int rows = transferManifestMapper.update(patch, Wrappers.<TransferManifestPO>lambdaUpdate()
                    .eq(TransferManifestPO::getId, manifest.getId())
                    .eq(TransferManifestPO::getStatus, ManifestStatus.IN_TRANSIT.name()));
            if (rows == 0) {
                throw new BizException("联单不在运输中，不能签收");
            }
            // 2. 许可余量硬闸：累计已接收 + 这趟实收不得盖过许可上限，条件更新原子把关，
            //    超了更新 0 行，整事务回滚，联单留在运输中，先把额度腾出来再签
            int bumped = treatmentUnitMapper.addReceivedWeight(manifest.getUnitId(), actualWeight);
            if (bumped == 0) {
                throw new BizException("处置单位许可余量不足，无法签收");
            }
            // 3. 签收留痕：实际过磅重量与签收时刻落在签收单上，一单一行
            bizNoService.inLock("SO", () -> {
                ManifestSignoffPO signoff = new ManifestSignoffPO();
                signoff.setId(IdUtil.getSnowflakeNextId());
                signoff.setSignoffNo(bizNoService.nextSignoffNo());
                signoff.setManifestId(manifest.getId());
                signoff.setReceivedWeight(actualWeight);
                signoff.setSignAt(manifest.getReceiveAt());
                manifestSignoffMapper.insert(signoff);
                return null;
            });
            return TransferManifestPoConverter.toDomain(transferManifestMapper.selectById(manifest.getId()));
        }));
    }

    @Override
    public Mono<TransferManifest> confirmDisposal(TransferManifest manifest, ManifestSignoff signoff) {
        return blocking(() -> txTemplate.execute(tx -> {
            // 1. 联单条件置已处置：只有 RECEIVED 推得动；手快重复确认时后到的更新 0 行，
            //    整事务回滚，同一张联单确认不了两回
            TransferManifestPO patch = new TransferManifestPO();
            patch.setStatus(ManifestStatus.DISPOSED.name());
            int rows = transferManifestMapper.update(patch, Wrappers.<TransferManifestPO>lambdaUpdate()
                    .eq(TransferManifestPO::getId, manifest.getId())
                    .eq(TransferManifestPO::getStatus, ManifestStatus.RECEIVED.name()));
            if (rows == 0) {
                throw new BizException("联单不在已签收状态，不能确认处置");
            }
            // 2. 签收单补齐处置信息：只认还没确认过的（confirm_at 仍空），
            //    已确认过的更新 0 行，整事务回滚
            ManifestSignoffPO signoffPatch = new ManifestSignoffPO();
            signoffPatch.setDisposedWeight(signoff.getDisposedWeight());
            signoffPatch.setDisposalMethod(signoff.getDisposalMethod());
            signoffPatch.setConfirmAt(signoff.getConfirmAt());
            int confirmed = manifestSignoffMapper.update(signoffPatch, Wrappers.<ManifestSignoffPO>lambdaUpdate()
                    .eq(ManifestSignoffPO::getId, signoff.getId())
                    .isNull(ManifestSignoffPO::getConfirmAt));
            if (confirmed == 0) {
                throw new BizException("该联单已确认过处置，不能重复确认");
            }
            // 3. 库存核销：这趟货当初挪出来的在库批次（manifest_id 挂着本联单、仍在库）置 DISPOSED，
            //    别再当成还压在库里；已核销过的批次条件不匹配，天然幂等，没有占用批次也没什么可动
            WasteStockPO writeOff = new WasteStockPO();
            writeOff.setStatus(StockStatus.DISPOSED.name());
            wasteStockMapper.update(writeOff, Wrappers.<WasteStockPO>lambdaUpdate()
                    .eq(WasteStockPO::getManifestId, manifest.getId())
                    .eq(WasteStockPO::getStatus, StockStatus.IN_STOCK.name()));
            return TransferManifestPoConverter.toDomain(transferManifestMapper.selectById(manifest.getId()));
        }));
    }

    @Override
    public Mono<PageResult<TransferManifest>> page(int pageNum, int pageSize, Long planId, Long sourceId,
                                                   String categoryCode, Long unitId, String status) {
        return this.<PageResult<TransferManifest>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<TransferManifestPO> wrapper = Wrappers.<TransferManifestPO>lambdaQuery()
                        .eq(planId != null, TransferManifestPO::getPlanId, planId)
                        .eq(sourceId != null, TransferManifestPO::getSourceId, sourceId)
                        .eq(categoryCode != null && !categoryCode.isBlank(),
                                TransferManifestPO::getCategoryCode, categoryCode)
                        .eq(unitId != null, TransferManifestPO::getUnitId, unitId)
                        .eq(status != null && !status.isBlank(), TransferManifestPO::getStatus, status)
                        .orderByDesc(TransferManifestPO::getId);
                List<TransferManifestPO> rows = transferManifestMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<TransferManifest> content = rows.stream()
                        .map(TransferManifestPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    /** 通用状态条件更新：只有 SUBMITTED 能推到目标状态，更新 0 行即已被别人审过。 */
    private TransferManifest transition(TransferManifest manifest, String targetStatus, String conflictMsg) {
        TransferManifestPO patch = new TransferManifestPO();
        patch.setStatus(targetStatus);
        int rows = transferManifestMapper.update(patch, Wrappers.<TransferManifestPO>lambdaUpdate()
                .eq(TransferManifestPO::getId, manifest.getId())
                .eq(TransferManifestPO::getStatus, ManifestStatus.SUBMITTED.name()));
        if (rows == 0) {
            throw new BizException(conflictMsg);
        }
        return TransferManifestPoConverter.toDomain(transferManifestMapper.selectById(manifest.getId()));
    }
}
