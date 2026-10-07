package com.somepro.infrastructure.persistence.hwaste;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.hwaste.model.CategoryStatus;
import com.somepro.domain.hwaste.model.PlanStatus;
import com.somepro.domain.hwaste.model.TransferPlan;
import com.somepro.domain.hwaste.repository.TransferPlanRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.infrastructure.persistence.base.BaseBlockingRepository;
import com.somepro.infrastructure.persistence.hwaste.converter.TransferPlanPoConverter;
import com.somepro.infrastructure.persistence.hwaste.po.TransferPlanPO;
import com.somepro.infrastructure.persistence.hwaste.po.WasteCategoryPO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 年度转移计划仓储适配器（基础设施层）。
 *
 * 并发约定：
 * - 立单的「查重 + 取号 + 插入」包在同一把 TP 锁里 —— 同一单位同一类别同一年度
 *   几乎同时递两份，只该成一份（del_flag 由 @TableLogic 自动追加，软删的组合不挡重新立单）。
 * - 申报 / 追加 / 批复 / 驳回都走条件更新：UPDATE ... WHERE id=? AND status=?，
 *   更新 0 行说明状态已被别人推走，后到的请求直接拒掉；追加因此不会把额度叠两回。
 */
@Repository
public class TransferPlanRepositoryImpl extends BaseBlockingRepository implements TransferPlanRepository {

    private final TransferPlanMapper transferPlanMapper;
    private final WasteCategoryMapper wasteCategoryMapper;
    private final BizNoService bizNoService;
    private final TransactionTemplate txTemplate;

    public TransferPlanRepositoryImpl(TransferPlanMapper transferPlanMapper,
                                      WasteCategoryMapper wasteCategoryMapper,
                                      BizNoService bizNoService,
                                      PlatformTransactionManager transactionManager) {
        this.transferPlanMapper = transferPlanMapper;
        this.wasteCategoryMapper = wasteCategoryMapper;
        this.bizNoService = bizNoService;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public Mono<TransferPlan> create(TransferPlan plan) {
        return blocking(() -> bizNoService.inLock("TP", () -> txTemplate.execute(tx -> {
            // 锁类别行：与「停用名录」互斥。停用后不再收该类别的新计划（含新草稿）。
            WasteCategoryPO category = wasteCategoryMapper.selectByCodeForUpdate(plan.getCategoryCode());
            if (category == null) {
                throw new BizException("危废类别不存在");
            }
            if (!CategoryStatus.ENABLED.name().equals(category.getStatus())) {
                throw new BizException("危废类别已停用，不能新建转移计划");
            }
            // 同一单位同一类别同一年度只准挂一份（del_flag 由 @TableLogic 自动追加，软删的组合不挡重新立单）
            Long dup = transferPlanMapper.selectCount(Wrappers.<TransferPlanPO>lambdaQuery()
                    .eq(TransferPlanPO::getSourceId, plan.getSourceId())
                    .eq(TransferPlanPO::getCategoryCode, plan.getCategoryCode())
                    .eq(TransferPlanPO::getPlanYear, plan.getPlanYear()));
            if (dup != null && dup > 0) {
                throw new BizException("该单位该类别该年度已存在转移计划，请勿重复申报");
            }
            TransferPlanPO po = TransferPlanPoConverter.toPo(plan);
            po.setId(IdUtil.getSnowflakeNextId());
            po.setPlanNo(bizNoService.nextPlanNo(plan.getPlanYear()));
            transferPlanMapper.insert(po);
            return TransferPlanPoConverter.toDomain(po);
        })));
    }

    @Override
    public Mono<TransferPlan> findById(Long id) {
        return blocking(() -> {
            TransferPlanPO po = transferPlanMapper.selectById(id);
            return po == null ? null : TransferPlanPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<TransferPlan> findByPlanNo(String planNo) {
        return blocking(() -> {
            TransferPlanPO po = transferPlanMapper.selectOne(Wrappers.<TransferPlanPO>lambdaQuery()
                    .eq(TransferPlanPO::getPlanNo, planNo));
            return po == null ? null : TransferPlanPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<TransferPlan> findBySourceCategoryYear(Long sourceId, String categoryCode, Integer planYear) {
        return blocking(() -> {
            TransferPlanPO po = transferPlanMapper.selectOne(Wrappers.<TransferPlanPO>lambdaQuery()
                    .eq(TransferPlanPO::getSourceId, sourceId)
                    .eq(TransferPlanPO::getCategoryCode, categoryCode)
                    .eq(TransferPlanPO::getPlanYear, planYear));
            return po == null ? null : TransferPlanPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<TransferPlan> updateDraft(TransferPlan plan) {
        // 只改申报量，且只有草稿 / 首轮驳回能改；条件更新兜底并发
        return blocking(() -> {
            TransferPlanPO patch = new TransferPlanPO();
            patch.setPlannedWeight(plan.getPlannedWeight());
            int rows = transferPlanMapper.update(patch, Wrappers.<TransferPlanPO>lambdaUpdate()
                    .eq(TransferPlanPO::getId, plan.getId())
                    .in(TransferPlanPO::getStatus, PlanStatus.DRAFT.name(), PlanStatus.REJECTED.name()));
            if (rows == 0) {
                throw new BizException("计划状态已变化，只有草稿或已驳回的计划才能修改");
            }
            return TransferPlanPoConverter.toDomain(transferPlanMapper.selectById(plan.getId()));
        });
    }

    @Override
    public Mono<TransferPlan> submit(TransferPlan plan) {
        return blocking(() -> transition(plan,
                List.of(PlanStatus.DRAFT.name(), PlanStatus.REJECTED.name()),
                "计划已申报或状态已变化，请勿重复申报"));
    }

    @Override
    public Mono<TransferPlan> appendSubmit(TransferPlan plan) {
        return blocking(() -> {
            // 条件更新：仅已批复能进追加申报，同时把追加量叠进申报总量；两笔并发只成一笔
            TransferPlanPO patch = new TransferPlanPO();
            patch.setStatus(PlanStatus.SUBMITTED.name());
            patch.setPlannedWeight(plan.getPlannedWeight());
            int rows = transferPlanMapper.update(patch, Wrappers.<TransferPlanPO>lambdaUpdate()
                    .eq(TransferPlanPO::getId, plan.getId())
                    .eq(TransferPlanPO::getStatus, PlanStatus.APPROVED.name()));
            if (rows == 0) {
                throw new BizException("追加申报并发冲突或计划状态已变化，该计划已有一笔追加在途");
            }
            return TransferPlanPoConverter.toDomain(transferPlanMapper.selectById(plan.getId()));
        });
    }

    @Override
    public Mono<TransferPlan> approve(TransferPlan plan) {
        return blocking(() -> {
            TransferPlanPO patch = new TransferPlanPO();
            patch.setStatus(PlanStatus.APPROVED.name());
            patch.setApprovedWeight(plan.getApprovedWeight());
            int rows = transferPlanMapper.update(patch, Wrappers.<TransferPlanPO>lambdaUpdate()
                    .eq(TransferPlanPO::getId, plan.getId())
                    .eq(TransferPlanPO::getStatus, PlanStatus.SUBMITTED.name()));
            if (rows == 0) {
                throw new BizException("只有已申报的计划才能批复，请勿重复批复");
            }
            return TransferPlanPoConverter.toDomain(transferPlanMapper.selectById(plan.getId()));
        });
    }

    @Override
    public Mono<TransferPlan> reject(TransferPlan plan) {
        return blocking(() -> {
            // 驳回条件更新：仅 SUBMITTED 可驳。申报量一并落库 ——
            // 追加驳回时领域已把申报总量退回追加前，首轮驳回申报量本来就没变，多写一次同值无妨。
            TransferPlanPO patch = new TransferPlanPO();
            patch.setStatus(plan.getStatus().name());
            patch.setPlannedWeight(plan.getPlannedWeight());
            int rows = transferPlanMapper.update(patch, Wrappers.<TransferPlanPO>lambdaUpdate()
                    .eq(TransferPlanPO::getId, plan.getId())
                    .eq(TransferPlanPO::getStatus, PlanStatus.SUBMITTED.name()));
            if (rows == 0) {
                throw new BizException("只有已申报的计划才能驳回");
            }
            return TransferPlanPoConverter.toDomain(transferPlanMapper.selectById(plan.getId()));
        });
    }

    @Override
    public Mono<PageResult<TransferPlan>> page(int pageNum, int pageSize, Long sourceId, String categoryCode,
                                               Integer planYear, String status) {
        return this.<PageResult<TransferPlan>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<TransferPlanPO> wrapper = Wrappers.<TransferPlanPO>lambdaQuery()
                        .eq(sourceId != null, TransferPlanPO::getSourceId, sourceId)
                        .eq(categoryCode != null && !categoryCode.isBlank(),
                                TransferPlanPO::getCategoryCode, categoryCode)
                        .eq(planYear != null, TransferPlanPO::getPlanYear, planYear)
                        .eq(status != null && !status.isBlank(), TransferPlanPO::getStatus, status)
                        .orderByDesc(TransferPlanPO::getId);
                List<TransferPlanPO> rows = transferPlanMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<TransferPlan> content = rows.stream()
                        .map(TransferPlanPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                PageHelper.clearPage();
            }
        });
    }

    /** 通用状态条件更新：状态在 expectStatuses 之内才推到 plan 自身的目标状态。 */
    private TransferPlan transition(TransferPlan plan, List<String> expectStatuses, String conflictMsg) {
        TransferPlanPO patch = new TransferPlanPO();
        patch.setStatus(plan.getStatus().name());
        int rows = transferPlanMapper.update(patch, Wrappers.<TransferPlanPO>lambdaUpdate()
                .eq(TransferPlanPO::getId, plan.getId())
                .in(TransferPlanPO::getStatus, expectStatuses));
        if (rows == 0) {
            throw new BizException(conflictMsg);
        }
        return TransferPlanPoConverter.toDomain(transferPlanMapper.selectById(plan.getId()));
    }
}
