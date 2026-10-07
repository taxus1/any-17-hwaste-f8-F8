package com.somepro.infrastructure.persistence.hwaste.converter;

import com.somepro.domain.hwaste.model.SourceStatus;
import com.somepro.domain.hwaste.model.WasteSource;
import com.somepro.infrastructure.persistence.hwaste.po.WasteSourcePO;

/**
 * WasteSourcePO（表）↔ WasteSource（领域）转换器（基础设施层）。
 * 状态在库里存字符串，在领域里是枚举，互转在这里收口。
 */
public final class WasteSourcePoConverter {

    private WasteSourcePoConverter() {
    }

    public static WasteSourcePO toPo(WasteSource domain) {
        WasteSourcePO po = new WasteSourcePO();
        po.setId(domain.getId());
        po.setSourceNo(domain.getSourceNo());
        po.setName(domain.getName());
        po.setCreditCode(domain.getCreditCode());
        po.setProvince(domain.getProvince());
        po.setCity(domain.getCity());
        po.setAddress(domain.getAddress());
        po.setContact(domain.getContact());
        po.setPhone(domain.getPhone());
        po.setStatus(domain.getStatus() == null ? null : domain.getStatus().name());
        po.setDelFlag(domain.getDelFlag());
        po.setCreateBy(domain.getCreateBy());
        po.setCreateTime(domain.getCreateTime());
        po.setUpdateBy(domain.getUpdateBy());
        po.setUpdateTime(domain.getUpdateTime());
        return po;
    }

    public static WasteSource toDomain(WasteSourcePO po) {
        WasteSource domain = new WasteSource();
        domain.setId(po.getId());
        domain.setSourceNo(po.getSourceNo());
        domain.setName(po.getName());
        domain.setCreditCode(po.getCreditCode());
        domain.setProvince(po.getProvince());
        domain.setCity(po.getCity());
        domain.setAddress(po.getAddress());
        domain.setContact(po.getContact());
        domain.setPhone(po.getPhone());
        domain.setStatus(po.getStatus() == null ? null : SourceStatus.valueOf(po.getStatus()));
        domain.setDelFlag(po.getDelFlag());
        domain.setCreateBy(po.getCreateBy());
        domain.setCreateTime(po.getCreateTime());
        domain.setUpdateBy(po.getUpdateBy());
        domain.setUpdateTime(po.getUpdateTime());
        return domain;
    }
}
