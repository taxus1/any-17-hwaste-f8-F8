package com.somepro.application.hwaste;

import com.somepro.common.exception.BizException;
import com.somepro.domain.hwaste.model.PlanStatus;
import com.somepro.domain.hwaste.model.TransferManifest;
import com.somepro.domain.hwaste.model.TreatmentUnit;
import com.somepro.domain.hwaste.model.WasteCategory;
import com.somepro.domain.hwaste.model.WasteSource;
import com.somepro.domain.hwaste.repository.TransferManifestRepository;
import com.somepro.domain.hwaste.repository.TransferPlanRepository;
import com.somepro.domain.hwaste.repository.TreatmentUnitRepository;
import com.somepro.domain.hwaste.repository.WasteCategoryRepository;
import com.somepro.domain.hwaste.repository.WasteSourceRepository;
import com.somepro.domain.shared.model.PageResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 电子转移联单用例编排（应用层）：提交 → 审批 / 退回 → 启运 → 签收，以及详情与多条件翻页。
 *
 * 提交一道道过门槛（规则落在领域对象，这里只做编排）：
 * 1. 产废单位、危废类别、处置单位都得真实存在，且产废单位得处在正常 ACTIVE ——
 *    单位被停用（停产 / 搬迁 / 整治）后，名下年度计划开不出新联单；已开出在途的老联单不受影响；
 * 2. 处置单位得处在正常 ACTIVE，且这趟货的类别在对方能接的范围里；
 * 3. 得有一份对得上的年度计划（同单位 + 同类别 + 同年度）且已批复 APPROVED，
 *    草稿、还没批、被驳回的都开不出联单；
 * 4. 类别名录标了不许跨省的，供废与收货两头不在同一个省就挡回去；
 * 5. 额度：这趟量连同该计划下已开出去的联单量，不能盖过计划批复总量 ——
 *    这道由仓储侧在取号锁内复核，并发两笔不会一起把额度用穿。
 *
 * 审批只走一道：已提交才能批 / 退，退回必须写明理由；已批过、退过、作废的不再来回审，
 * 由领域状态机与仓储条件更新（仅 SUBMITTED 生效）双道兜底。
 *
 * 启运只认已审批：APPROVED 才启得动，落 IN_TRANSIT 并记下启运时刻；
 * 还在提交、被退回、已走完的单子启不动，仓储条件更新（仅 APPROVED 生效）兜底。
 *
 * 签收只认在途：IN_TRANSIT 才签得了，落 RECEIVED 并记下签收时刻。
 * 签收重量认实际过磅的数，不拿申报量硬顶；处置单位的许可余量两道把关 ——
 * 这里先拿单位快照过一道（requireLicenseHeadroom），仓储侧再用条件更新原子加码，
 * 累计已接收 + 这趟实收盖过许可上限就整单回滚，先把额度腾出来再签。
 */
@Service
public class TransferManifestAppService {

    private final TransferManifestRepository transferManifestRepository;
    private final TransferPlanRepository transferPlanRepository;
    private final WasteSourceRepository wasteSourceRepository;
    private final WasteCategoryRepository wasteCategoryRepository;
    private final TreatmentUnitRepository treatmentUnitRepository;

    public TransferManifestAppService(TransferManifestRepository transferManifestRepository,
                                      TransferPlanRepository transferPlanRepository,
                                      WasteSourceRepository wasteSourceRepository,
                                      WasteCategoryRepository wasteCategoryRepository,
                                      TreatmentUnitRepository treatmentUnitRepository) {
        this.transferManifestRepository = transferManifestRepository;
        this.transferPlanRepository = transferPlanRepository;
        this.wasteSourceRepository = wasteSourceRepository;
        this.wasteCategoryRepository = wasteCategoryRepository;
        this.treatmentUnitRepository = treatmentUnitRepository;
    }

    /**
     * 提交联单：门槛全过后落 SUBMITTED。年度默认取当年，planYear 可显式指定。
     * 联单编号由仓储侧分配；额度复核与取号、插入包在同一把锁里。
     */
    public Mono<TransferManifest> submit(Long sourceId, String categoryCode, Long unitId,
                                         String transporter, BigDecimal transferWeight, Integer planYear) {
        if (transferWeight == null || transferWeight.signum() <= 0) {
            return Mono.error(new BizException("申报转移重量必须大于 0"));
        }
        int year = planYear != null ? planYear : LocalDate.now().getYear();
        Mono<WasteSource> source = wasteSourceRepository.findById(sourceId)
                .switchIfEmpty(Mono.error(new BizException("产废单位不存在")));
        Mono<WasteCategory> category = wasteCategoryRepository.findByCode(categoryCode)
                .switchIfEmpty(Mono.error(new BizException("危废类别不存在")));
        Mono<TreatmentUnit> unit = treatmentUnitRepository.findById(unitId)
                .switchIfEmpty(Mono.error(new BizException("处置单位不存在")));
        return Mono.zip(source, category, unit).flatMap(tuple -> {
            WasteSource src = tuple.getT1();
            WasteCategory cat = tuple.getT2();
            TreatmentUnit un = tuple.getT3();
            if (!src.isActive()) {
                return Mono.error(new BizException("产废单位非正常状态，名下年度计划不能开具新联单"));
            }
            if (!cat.isEnabled()) {
                return Mono.error(new BizException("危废类别已停用，不能开具新联单"));
            }
            if (!un.isActive()) {
                return Mono.error(new BizException("处置单位非正常状态，不能接收新联单"));
            }
            if (!un.canAccept(categoryCode)) {
                return Mono.error(new BizException("处置单位不能接收该危废类别"));
            }
            return transferPlanRepository.findBySourceCategoryYear(sourceId, categoryCode, year)
                    .switchIfEmpty(Mono.error(new BizException("没有对应的年度计划，不能开具联单")))
                    .flatMap(plan -> {
                        if (plan.getStatus() != PlanStatus.APPROVED) {
                            return Mono.error(new BizException("年度计划未批复，不能开具联单"));
                        }
                        boolean cross = src.getProvince() != null && un.getProvince() != null
                                && !src.getProvince().equals(un.getProvince());
                        if (cross && cat.isCrossProvinceRestricted()) {
                            return Mono.error(new BizException("该危废类别限制跨省转移，不得跨省开具联单"));
                        }
                        TransferManifest manifest = TransferManifest.create(plan.getId(), sourceId, unitId,
                                categoryCode, transporter, transferWeight, cross);
                        return transferManifestRepository.create(manifest, plan.getApprovedWeight());
                    });
        });
    }

    /** 审批：已提交 → 已审批；已批过 / 退过 / 作废的别再来回审。 */
    public Mono<TransferManifest> approve(Long manifestId, String manifestNo) {
        return load(manifestId, manifestNo).flatMap(manifest -> {
            manifest.approve();
            return transferManifestRepository.approve(manifest);
        });
    }

    /** 退回：已提交 → 已退回，必须写明退回理由。 */
    public Mono<TransferManifest> reject(Long manifestId, String manifestNo, String reason) {
        if (reason == null || reason.isBlank()) {
            return Mono.error(new BizException("退回必须写明理由"));
        }
        return load(manifestId, manifestNo).flatMap(manifest -> {
            manifest.reject(reason);
            return transferManifestRepository.reject(manifest);
        });
    }

    /** 启运：已审批 → 运输中，记下启运时刻；还在提交、被退回、已走完的单子启不动。 */
    public Mono<TransferManifest> depart(Long manifestId, String manifestNo) {
        return load(manifestId, manifestNo).flatMap(manifest -> {
            manifest.depart();
            return transferManifestRepository.depart(manifest);
        });
    }

    /**
     * 签收：运输中 → 已签收，记下签收时刻。
     * 重量认实际过磅的 actualWeight（跟申报量对不齐也照实收落账）；
     * 处置单位许可余量不够就挡回去，先把额度腾出来再签。
     */
    public Mono<TransferManifest> receive(Long manifestId, String manifestNo, BigDecimal actualWeight) {
        if (actualWeight == null || actualWeight.signum() <= 0) {
            return Mono.error(new BizException("签收重量必须大于 0"));
        }
        return load(manifestId, manifestNo).flatMap(manifest -> {
            manifest.receive(actualWeight);
            return treatmentUnitRepository.findById(manifest.getUnitId())
                    .switchIfEmpty(Mono.error(new BizException("处置单位不存在")))
                    .flatMap(unit -> {
                        unit.requireLicenseHeadroom(actualWeight);
                        return transferManifestRepository.receive(manifest, actualWeight);
                    });
        });
    }

    /** 联单详情：按 id 或编号查。 */
    public Mono<TransferManifest> detail(Long manifestId, String manifestNo) {
        return load(manifestId, manifestNo);
    }

    /** 翻联单：计划 / 单位 / 类别 / 处置单位 / 状态随意挑，一个都不填分页列全。 */
    public Mono<PageResult<TransferManifest>> page(int pageNum, int pageSize, Long planId, Long sourceId,
                                                   String categoryCode, Long unitId, String status) {
        return transferManifestRepository.page(pageNum, pageSize, planId, sourceId, categoryCode, unitId, status);
    }

    /** 按 id 或编号加载联单；两个都不传或查不到都视为业务失败。 */
    private Mono<TransferManifest> load(Long manifestId, String manifestNo) {
        Mono<TransferManifest> found;
        if (manifestId != null) {
            found = transferManifestRepository.findById(manifestId);
        } else if (manifestNo != null && !manifestNo.isBlank()) {
            found = transferManifestRepository.findByManifestNo(manifestNo.trim());
        } else {
            return Mono.error(new BizException("manifestId 或 manifestNo 必传其一"));
        }
        return found.switchIfEmpty(Mono.error(new BizException("转移联单不存在")));
    }
}
