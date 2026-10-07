package com.somepro.interfaces.rest.hwaste.converter;

import com.somepro.domain.hwaste.model.WasteSource;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.interfaces.rest.hwaste.vo.PageVO;
import com.somepro.interfaces.rest.hwaste.vo.WasteSourceVO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * WasteSource（领域）→ 对外 VO 转换器（用户接口层）。
 */
public final class WasteSourceVoConverter {

    private WasteSourceVoConverter() {
    }

    public static WasteSourceVO toVo(WasteSource domain) {
        return new WasteSourceVO(
                domain.getId(),
                domain.getSourceNo(),
                domain.getName(),
                domain.getCreditCode(),
                domain.getProvince(),
                domain.getCity(),
                domain.getAddress(),
                domain.getContact(),
                domain.getPhone(),
                domain.getStatus() == null ? null : domain.getStatus().name(),
                domain.getCreateBy(),
                domain.getCreateTime(),
                domain.getUpdateBy(),
                domain.getUpdateTime());
    }

    public static PageVO<WasteSourceVO> toPageVo(PageResult<WasteSource> page) {
        List<WasteSourceVO> content = page.content().stream()
                .map(WasteSourceVoConverter::toVo)
                .collect(Collectors.toList());
        return new PageVO<>(content, page.total(), page.pageNum(), page.pageSize(), page.totalPages());
    }
}
