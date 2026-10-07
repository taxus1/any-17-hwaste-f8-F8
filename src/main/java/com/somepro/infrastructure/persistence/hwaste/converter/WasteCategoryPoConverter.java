package com.somepro.infrastructure.persistence.hwaste.converter;

import com.somepro.domain.hwaste.model.CategoryStatus;
import com.somepro.domain.hwaste.model.HazardType;
import com.somepro.domain.hwaste.model.WasteCategory;
import com.somepro.infrastructure.persistence.hwaste.po.WasteCategoryPO;

/**
 * WasteCategoryPO（表）↔ WasteCategory（领域）转换器（基础设施层）。
 * 状态 / 危险特性在库里存字符串，在领域里是枚举，互转在这里收口。
 */
public final class WasteCategoryPoConverter {

    private WasteCategoryPoConverter() {
    }

    public static WasteCategoryPO toPo(WasteCategory domain) {
        WasteCategoryPO po = new WasteCategoryPO();
        po.setId(domain.getId());
        po.setCategoryCode(domain.getCategoryCode());
        po.setName(domain.getName());
        po.setHazardType(domain.getHazardType() == null ? null : domain.getHazardType().name());
        po.setCrossProvince(domain.getCrossProvince());
        po.setStatus(domain.getStatus() == null ? null : domain.getStatus().name());
        po.setDelFlag(domain.getDelFlag());
        po.setCreateBy(domain.getCreateBy());
        po.setCreateTime(domain.getCreateTime());
        po.setUpdateBy(domain.getUpdateBy());
        po.setUpdateTime(domain.getUpdateTime());
        return po;
    }

    public static WasteCategory toDomain(WasteCategoryPO po) {
        WasteCategory domain = new WasteCategory();
        domain.setId(po.getId());
        domain.setCategoryCode(po.getCategoryCode());
        domain.setName(po.getName());
        domain.setHazardType(po.getHazardType() == null ? null : HazardType.valueOf(po.getHazardType()));
        domain.setCrossProvince(po.getCrossProvince());
        domain.setStatus(po.getStatus() == null ? null : CategoryStatus.valueOf(po.getStatus()));
        domain.setDelFlag(po.getDelFlag());
        domain.setCreateBy(po.getCreateBy());
        domain.setCreateTime(po.getCreateTime());
        domain.setUpdateBy(po.getUpdateBy());
        domain.setUpdateTime(po.getUpdateTime());
        return domain;
    }
}
