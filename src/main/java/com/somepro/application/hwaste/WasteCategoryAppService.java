package com.somepro.application.hwaste;

import com.somepro.common.exception.BizException;
import com.somepro.domain.hwaste.model.CategoryImpact;
import com.somepro.domain.hwaste.model.CategoryStatus;
import com.somepro.domain.hwaste.model.DisableResult;
import com.somepro.domain.hwaste.model.HazardType;
import com.somepro.domain.hwaste.model.WasteCategory;
import com.somepro.domain.hwaste.repository.WasteCategoryRepository;
import com.somepro.domain.shared.model.PageResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 危废类别名录用例编排（应用层）：录入、修改、查看、停用，以及停用前的影响面评估。
 *
 * 一条名录一个类别：代码照 HW08 写法排，一个代码只归一个类别，重复代码挡回；
 * 名录留类别名称、危险特性（五档）和跨省标志（1 不许跨省 / 0 允许）；状态不写按启用落。
 *
 * 停用前先看影响面（在库批次 / 重量、在办联单、待批计划）：有在库批次或在办联单时，
 * 不确认（force=true）不硬停，影响面文案随失败原因透给调用方。停用只挡新单
 * （新入库 / 新联单 / 新计划），先前已在办的单子照走；与开新单的并发由仓储侧
 * 类别行锁裁决，账不会两头对不上。
 */
@Service
public class WasteCategoryAppService {

    private final WasteCategoryRepository wasteCategoryRepository;

    public WasteCategoryAppService(WasteCategoryRepository wasteCategoryRepository) {
        this.wasteCategoryRepository = wasteCategoryRepository;
    }

    /** 录入新名录：代码唯一、危险特性五档、跨省标志 0/1；状态没写按启用 ENABLED 落。 */
    public Mono<WasteCategory> create(String categoryCode, String name, String hazardType,
                                      Integer crossProvince) {
        return Mono.defer(() -> {
            WasteCategory category = WasteCategory.create(categoryCode, name, parseHazard(hazardType), crossProvince);
            return wasteCategoryRepository.create(category);
        });
    }

    /** 改名录：只动类别名称、危险特性、跨省标志；代码不能改、状态走停用接口。 */
    public Mono<WasteCategory> update(Long id, String categoryCode, String name, String hazardType,
                                      Integer crossProvince) {
        return load(id, categoryCode).flatMap(category -> {
            category.edit(name, parseHazard(hazardType), crossProvince);
            return wasteCategoryRepository.update(category);
        });
    }

    /** 查看：id 或类别代码传其一。 */
    public Mono<WasteCategory> detail(Long id, String categoryCode) {
        return load(id, categoryCode);
    }

    /** 名录翻页：可按危险特性、状态过滤；每条都带类别代码。 */
    public Mono<PageResult<WasteCategory>> page(int pageNum, int pageSize, String hazardType, String status) {
        HazardType hazard = parseHazardOrNull(hazardType);
        CategoryStatus categoryStatus = parseStatusOrNull(status);
        return wasteCategoryRepository.page(pageNum, pageSize, hazard, categoryStatus);
    }

    /** 停用前影响面评估：在库批次数与重量、未走完联单张数、待批计划份数。 */
    public Mono<CategoryImpact> impact(Long id, String categoryCode) {
        return load(id, categoryCode).flatMap(category -> wasteCategoryRepository.assessImpact(
                category.getCategoryCode()));
    }

    /**
     * 停用：有在库批次或在办联单时必须 force=true（表示已看清影响面、确认停用）。
     * 返回停用后的名录与锁内取的影响面快照。
     */
    public Mono<DisableResult> disable(Long id, String categoryCode, boolean force) {
        return load(id, categoryCode)
                .flatMap(category -> wasteCategoryRepository.disable(category.getCategoryCode(), force));
    }

    /** 按 id 或类别代码加载名录；两个都不传或查不到都视为业务失败。 */
    private Mono<WasteCategory> load(Long id, String categoryCode) {
        Mono<WasteCategory> found;
        if (id != null) {
            found = wasteCategoryRepository.findById(id);
        } else if (categoryCode != null && !categoryCode.isBlank()) {
            found = wasteCategoryRepository.findByCode(categoryCode.trim().toUpperCase());
        } else {
            return Mono.error(new BizException("id 或 categoryCode 必传其一"));
        }
        return found.switchIfEmpty(Mono.error(new BizException("危废类别不存在")));
    }

    private static HazardType parseHazard(String hazardType) {
        HazardType parsed = parseHazardOrNull(hazardType);
        if (parsed == null) {
            throw new BizException("危险特性不能为空，取值：TOXIC / CORROSIVE / FLAMMABLE / REACTIVE / INFECTIOUS");
        }
        return parsed;
    }

    private static HazardType parseHazardOrNull(String hazardType) {
        if (hazardType == null || hazardType.isBlank()) {
            return null;
        }
        try {
            return HazardType.valueOf(hazardType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("危险特性取值非法，取值：TOXIC / CORROSIVE / FLAMMABLE / REACTIVE / INFECTIOUS");
        }
    }

    private static CategoryStatus parseStatusOrNull(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return CategoryStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("状态取值非法，取值：ENABLED / DISABLED");
        }
    }
}
