package com.somepro.application.hwaste;

import com.somepro.application.hwaste.port.PlanAppendClaim;
import com.somepro.application.hwaste.port.PlanAppendPort;
import com.somepro.common.exception.BizException;
import com.somepro.domain.hwaste.model.PlanDetail;
import com.somepro.domain.hwaste.model.TransferPlan;
import com.somepro.domain.hwaste.repository.TransferPlanRepository;
import com.somepro.domain.hwaste.repository.WasteCategoryRepository;
import com.somepro.domain.hwaste.repository.WasteSourceRepository;
import com.somepro.domain.hwaste.repository.WasteStockRepository;
import com.somepro.domain.shared.model.PageResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * 年度转移计划用例编排（应用层）：立单（草稿）→ 改量 → 申报 → 批复 / 驳回，
 * 已批复计划当年还能追加申报（追加 → 再批复 / 驳回），以及详情与多条件翻页。
 *
 * 规则落在领域对象 {@link TransferPlan}，这里只做编排：
 * 查在库总量、校验单位 / 类别、加载聚合、追加占用（Redis）与条件更新（DB）协同、落库。
 *
 * 追加并发由两道闸兜：Redis 占坑（SETNX，两笔几乎同时只成一笔）+ DB 条件更新
 * （只 APPROVED → SUBMITTED，后到者更新 0 行）。追加在途时批复额度保持原值不放宽。
 */
@Service
public class TransferPlanAppService {

    private final TransferPlanRepository transferPlanRepository;
    private final WasteStockRepository wasteStockRepository;
    private final WasteSourceRepository wasteSourceRepository;
    private final WasteCategoryRepository wasteCategoryRepository;
    private final PlanAppendPort planAppendPort;

    public TransferPlanAppService(TransferPlanRepository transferPlanRepository,
                                  WasteStockRepository wasteStockRepository,
                                  WasteSourceRepository wasteSourceRepository,
                                  WasteCategoryRepository wasteCategoryRepository,
                                  PlanAppendPort planAppendPort) {
        this.transferPlanRepository = transferPlanRepository;
        this.wasteStockRepository = wasteStockRepository;
        this.wasteSourceRepository = wasteSourceRepository;
        this.wasteCategoryRepository = wasteCategoryRepository;
        this.planAppendPort = planAppendPort;
    }

    /**
     * 立单落草稿：单位 / 类别得真实存在；同单位 + 同类别 + 同年度只准挂一份，重复由仓储侧挡回。
     * 申报量是否在在库线内到申报时再算（草稿允许先建）。
     */
    public Mono<TransferPlan> create(Long sourceId, String categoryCode, Integer planYear,
                                     BigDecimal plannedWeight) {
        return Mono.defer(() -> {
            TransferPlan plan = TransferPlan.create(sourceId, categoryCode, planYear, plannedWeight);
            return wasteSourceRepository.findById(plan.getSourceId())
                    .switchIfEmpty(Mono.error(new BizException("产废单位不存在")))
                    .then(wasteCategoryRepository.findByCode(plan.getCategoryCode()))
                    .switchIfEmpty(Mono.error(new BizException("危废类别不存在")))
                    .flatMap(category -> category.isEnabled()
                            ? Mono.empty()
                            : Mono.error(new BizException("危废类别已停用，不能新建转移计划")))
                    .then(transferPlanRepository.create(plan));
        });
    }

    /** 改量：只有草稿 / 首轮被驳回能改。 */
    public Mono<TransferPlan> edit(Long planId, String planNo, BigDecimal plannedWeight) {
        return load(planId, planNo).flatMap(plan -> {
            plan.edit(plannedWeight);
            return transferPlanRepository.updateDraft(plan);
        });
    }

    /** 首轮申报：草稿 / 驳回 → 已申报；申报量不能盖过当前在库总量。 */
    public Mono<TransferPlan> submit(Long planId, String planNo) {
        return load(planId, planNo).flatMap(plan ->
                wasteStockRepository.sumInStock(plan.getSourceId(), plan.getCategoryCode())
                        .flatMap(stock -> {
                            plan.submit(stock);
                            return transferPlanRepository.submit(plan);
                        }));
    }

    /**
     * 追加申报：已批复计划当年再发起一次。累计申报量（原申报 + 追加）不能盖过在库总量。
     * Redis 先原子占坑（同时两笔只成一笔），DB 再条件更新 APPROVED → SUBMITTED；
     * 条件更新没行（已被另一笔抢先推走）就把占坑撤掉，报给后到者。
     */
    public Mono<TransferPlan> appendSubmit(Long planId, String planNo, BigDecimal appendWeight) {
        if (appendWeight == null || appendWeight.signum() <= 0) {
            return Mono.error(new BizException("追加重量必须大于 0"));
        }
        return load(planId, planNo).flatMap(plan ->
                planAppendPort.find(plan.getId()).flatMap(existing -> {
                    if (existing.isDone()) {
                        return Mono.<TransferPlan>error(new BizException("该计划当年追加已批复，不能再次追加"));
                    }
                    return Mono.<TransferPlan>error(new BizException("该计划已有追加在途，请勿重复追加"));
                }).switchIfEmpty(
                        wasteStockRepository.sumInStock(plan.getSourceId(), plan.getCategoryCode())
                                .flatMap(stock -> {
                                    BigDecimal plannedBefore = plan.getPlannedWeight();
                                    BigDecimal approvedBefore = plan.getApprovedWeight();
                                    plan.beginAppend(appendWeight, stock);
                                    PlanAppendClaim claim = new PlanAppendClaim(
                                            plan.getId(), appendWeight, plannedBefore, approvedBefore, false);
                                    return planAppendPort.begin(claim).flatMap(acquired -> {
                                        if (!acquired) {
                                            return Mono.error(new BizException("追加申报并发冲突，请稍后重试"));
                                        }
                                        return transferPlanRepository.appendSubmit(plan)
                                                .onErrorResume(e -> planAppendPort.cancel(plan.getId())
                                                        .then(Mono.error(e)));
                                    });
                                })));
    }

    /**
     * 批复：已申报 → 已批复，approvedWeight 传批复后的额度总量。
     * 首轮批复不能超申报量；追加批复还要带上「不低于原批复量」的下限校验。
     */
    public Mono<TransferPlan> approve(Long planId, String planNo, BigDecimal approvedWeight) {
        if (approvedWeight == null || approvedWeight.signum() < 0) {
            return Mono.error(new BizException("批复重量不能为空且不能为负"));
        }
        return load(planId, planNo).flatMap(plan ->
                planAppendPort.find(plan.getId())
                        .defaultIfEmpty(new PlanAppendClaim(plan.getId(), null, null,
                                plan.getApprovedWeight(), false))
                        .flatMap(claim -> {
                            plan.approve(approvedWeight, claim.getApprovedWeightBeforeAppend());
                            return transferPlanRepository.approve(plan).flatMap(saved ->
                                    claim.getAppendWeight() != null
                                            ? planAppendPort.finish(plan.getId()).thenReturn(saved)
                                            : Mono.just(saved));
                        }));
    }

    /**
     * 驳回：必须写明理由。有追加在途 → 追加驳回，申报量退回、状态回已批复、占用清除（当年可再追加）；
     * 否则为首轮驳回 → 已驳回，改量后可重新申报。
     */
    public Mono<TransferPlan> reject(Long planId, String planNo, String reason) {
        if (reason == null || reason.isBlank()) {
            return Mono.error(new BizException("驳回必须写明理由"));
        }
        return load(planId, planNo).flatMap(plan ->
                planAppendPort.find(plan.getId())
                        .defaultIfEmpty(new PlanAppendClaim(plan.getId(), null, null,
                                plan.getApprovedWeight(), false))
                        .flatMap(claim -> {
                            if (claim.getAppendWeight() != null) {
                                plan.rejectAppend(reason, claim.getPlannedWeightBeforeAppend());
                                return transferPlanRepository.reject(plan)
                                        .flatMap(saved -> planAppendPort.cancel(plan.getId()).thenReturn(saved));
                            }
                            plan.reject(reason);
                            return transferPlanRepository.reject(plan);
                        }));
    }

    /** 详情：申报量、批复量、该单位该类别当前在库总量一并带出。 */
    public Mono<PlanDetail> detail(Long planId, String planNo) {
        return load(planId, planNo).flatMap(plan ->
                wasteStockRepository.sumInStock(plan.getSourceId(), plan.getCategoryCode())
                        .map(stock -> PlanDetail.of(plan, stock)));
    }

    /** 翻计划：单位 / 类别 / 年度 / 状态随意挑，一个都不填分页列全。 */
    public Mono<PageResult<TransferPlan>> page(int pageNum, int pageSize, Long sourceId, String categoryCode,
                                               Integer planYear, String status) {
        return transferPlanRepository.page(pageNum, pageSize, sourceId, categoryCode, planYear, status);
    }

    /** 按 id 或编号加载计划；两个都不传或查不到都视为业务失败。 */
    private Mono<TransferPlan> load(Long planId, String planNo) {
        Mono<TransferPlan> found;
        if (planId != null) {
            found = transferPlanRepository.findById(planId);
        } else if (planNo != null && !planNo.isBlank()) {
            found = transferPlanRepository.findByPlanNo(planNo.trim());
        } else {
            return Mono.error(new BizException("planId 或 planNo 必传其一"));
        }
        return found.switchIfEmpty(Mono.error(new BizException("转移计划不存在")));
    }
}
