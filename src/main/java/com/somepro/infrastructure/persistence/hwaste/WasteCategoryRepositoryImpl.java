package com.somepro.infrastructure.persistence.hwaste;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.hwaste.model.CategoryImpact;
import com.somepro.domain.hwaste.model.CategoryStatus;
import com.somepro.domain.hwaste.model.DisableResult;
import com.somepro.domain.hwaste.model.HazardType;
import com.somepro.domain.hwaste.model.WasteCategory;
import com.somepro.domain.hwaste.repository.WasteCategoryRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.persistence.base.BaseBlockingRepository;
import com.somepro.infrastructure.persistence.hwaste.converter.WasteCategoryPoConverter;
import com.somepro.infrastructure.persistence.hwaste.po.WasteCategoryPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 危废类别名录仓储适配器（基础设施层）。
 *
 * 并发约定（这是本模块的关键账）：
 * - 停用（{@link #disable}）与「开新联单 / 新入库 / 新计划」都在各自事务里先
 *   {@code SELECT ... FOR UPDATE} 锁类别行，拿到锁后看到的状态即裁决依据 ——
 *   要么类别仍启用、单子照开（停用在它提交后才生效），要么类别已停用、单子挡回，
 *   不会出现「名录已停用、却挂着停用之后新开的单子」。
 * - 停用的「锁行 → 取影响面 → 条件更新 ENABLED→DISABLED」在同一事务内，
 *   影响面就是这一刀落下那一刻的快照。
 * - 新名录的查重 + 插入在 WC 锁内串行，库表 uk_category_code 唯一约束最后兜底。
 */
@Repository
public class WasteCategoryRepositoryImpl extends BaseBlockingRepository implements WasteCategoryRepository {

    private final WasteCategoryMapper wasteCategoryMapper;
    private final WasteStockMapper wasteStockMapper;
    private final TransferManifestMapper transferManifestMapper;
    private final TransferPlanMapper transferPlanMapper;
    private final BizNoService bizNoService;
    private final TransactionTemplate txTemplate;

    public WasteCategoryRepositoryImpl(WasteCategoryMapper wasteCategoryMapper,
                                       WasteStockMapper wasteStockMapper,
                                       TransferManifestMapper transferManifestMapper,
                                       TransferPlanMapper transferPlanMapper,
                                       BizNoService bizNoService,
                                       PlatformTransactionManager transactionManager) {
        this.wasteCategoryMapper = wasteCategoryMapper;
        this.wasteStockMapper = wasteStockMapper;
        this.transferManifestMapper = transferManifestMapper;
        this.transferPlanMapper = transferPlanMapper;
        this.bizNoService = bizNoService;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<WasteCategory> findByCode(String categoryCode) {
        return blocking(() -> {
            WasteCategoryPO po = wasteCategoryMapper.selectOne(Wrappers.<WasteCategoryPO>lambdaQuery()
                    .eq(WasteCategoryPO::getCategoryCode, categoryCode));
            return po == null ? null : WasteCategoryPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<WasteCategory> findById(Long id) {
        return blocking(() -> {
            WasteCategoryPO po = wasteCategoryMapper.selectById(id);
            return po == null ? null : WasteCategoryPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PageResult<WasteCategory>> page(int pageNum, int pageSize, HazardType hazardType,
                                                 CategoryStatus status) {
        return this.<PageResult<WasteCategory>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<WasteCategoryPO> wrapper = Wrappers.<WasteCategoryPO>lambdaQuery()
                        .eq(hazardType != null, WasteCategoryPO::getHazardType,
                                hazardType == null ? null : hazardType.name())
                        .eq(status != null, WasteCategoryPO::getStatus, status == null ? null : status.name())
                        .orderByAsc(WasteCategoryPO::getCategoryCode);
                List<WasteCategoryPO> rows = wasteCategoryMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<WasteCategory> content = rows.stream()
                        .map(WasteCategoryPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    @Override
    public Mono<WasteCategory> create(WasteCategory category) {
        return blocking(() -> bizNoService.inLock("WC", () -> {
            Long dup = wasteCategoryMapper.selectCount(Wrappers.<WasteCategoryPO>lambdaQuery()
                    .eq(WasteCategoryPO::getCategoryCode, category.getCategoryCode()));
            if (dup != null && dup > 0) {
                throw new BizException("类别代码 " + category.getCategoryCode() + " 已存在，一个代码只归一个类别");
            }
            WasteCategoryPO po = WasteCategoryPoConverter.toPo(category);
            po.setId(IdUtil.getSnowflakeNextId());
            try {
                wasteCategoryMapper.insert(po);
            } catch (DuplicateKeyException e) {
                // 几乎同时递两份同一代码时唯一约束兜底
                throw new BizException("类别代码 " + category.getCategoryCode() + " 已存在，一个代码只归一个类别");
            }
            return WasteCategoryPoConverter.toDomain(po);
        }));
    }

    @Override
    public Mono<WasteCategory> update(WasteCategory category) {
        return blocking(() -> {
            // 只改 name / hazard_type / cross_province；category_code、status 绝不由此接口改动
            WasteCategoryPO patch = new WasteCategoryPO();
            patch.setName(category.getName());
            patch.setHazardType(category.getHazardType().name());
            patch.setCrossProvince(category.getCrossProvince());
            int rows = wasteCategoryMapper.update(patch, Wrappers.<WasteCategoryPO>lambdaUpdate()
                    .eq(WasteCategoryPO::getId, category.getId()));
            if (rows == 0) {
                throw new BizException("危废类别不存在");
            }
            return WasteCategoryPoConverter.toDomain(wasteCategoryMapper.selectById(category.getId()));
        });
    }

    @Override
    public Mono<CategoryImpact> assessImpact(String categoryCode) {
        return blocking(() -> loadImpact(categoryCode.trim()));
    }

    @Override
    public Mono<DisableResult> disable(String categoryCode, boolean force) {
        String code = categoryCode.trim();
        return blocking(() -> txTemplate.execute(tx -> {
            // 1. 锁类别行：与开新单的事务互斥，前后脚竞态在这里裁决
            WasteCategoryPO po = wasteCategoryMapper.selectByCodeForUpdate(code);
            if (po == null) {
                throw new BizException("危废类别不存在");
            }
            if (CategoryStatus.DISABLED.name().equals(po.getStatus())) {
                throw new BizException("危废类别 " + code + " 已停用，无需重复停用");
            }
            // 2. 锁内取影响面快照：这就是停用这一刻牵动的账
            CategoryImpact impact = computeImpact(po.getId(), code);
            // 3. 有在库批次 / 在办联单时不给明确确认不硬停，把影响面文案透传给调用方
            if (impact.blocksDisable() && !force) {
                throw new BizException(impact.describe()
                        + "。确认仍要停用请带 force=true 再提交（停用只挡新单，老单子照走）");
            }
            // 4. 条件更新：只有 ENABLED 推得动，并发重复停用时后到的更新 0 行
            WasteCategoryPO patch = new WasteCategoryPO();
            patch.setStatus(CategoryStatus.DISABLED.name());
            int rows = wasteCategoryMapper.update(patch, Wrappers.<WasteCategoryPO>lambdaUpdate()
                    .eq(WasteCategoryPO::getId, po.getId())
                    .eq(WasteCategoryPO::getStatus, CategoryStatus.ENABLED.name()));
            if (rows == 0) {
                throw new BizException("危废类别 " + code + " 已被停用，本次停用未生效");
            }
            WasteCategory disabled = WasteCategoryPoConverter.toDomain(
                    wasteCategoryMapper.selectById(po.getId()));
            return new DisableResult(disabled, impact);
        }));
    }

    /** 只读影响面：类别不存在直接报业务失败。 */
    private CategoryImpact loadImpact(String code) {
        WasteCategoryPO po = wasteCategoryMapper.selectOne(Wrappers.<WasteCategoryPO>lambdaQuery()
                .eq(WasteCategoryPO::getCategoryCode, code));
        if (po == null) {
            throw new BizException("危废类别不存在");
        }
        return computeImpact(po.getId(), code);
    }

    private CategoryImpact computeImpact(Long categoryId, String code) {
        long batchCount = wasteStockMapper.countInStockByCategory(code);
        var weight = wasteStockMapper.sumInStockWeightByCategory(code);
        long openManifests = transferManifestMapper.countOpenByCategory(code);
        long pendingPlans = transferPlanMapper.countPendingByCategory(code);
        return new CategoryImpact(categoryId, code, batchCount, weight, openManifests, pendingPlans);
    }
}
