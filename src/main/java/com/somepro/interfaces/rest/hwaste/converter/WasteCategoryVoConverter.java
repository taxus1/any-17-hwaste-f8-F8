package com.somepro.interfaces.rest.hwaste.converter;

import com.somepro.domain.hwaste.model.CategoryImpact;
import com.somepro.domain.hwaste.model.DisableResult;
import com.somepro.domain.hwaste.model.WasteCategory;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.interfaces.rest.hwaste.vo.CategoryImpactVO;
import com.somepro.interfaces.rest.hwaste.vo.DisableCategoryVO;
import com.somepro.interfaces.rest.hwaste.vo.PageVO;
import com.somepro.interfaces.rest.hwaste.vo.WasteCategoryVO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * WasteCategory（领域）→ 对外 VO 转换器（用户接口层）。
 */
public final class WasteCategoryVoConverter {

    private WasteCategoryVoConverter() {
    }

    public static WasteCategoryVO toVo(WasteCategory domain) {
        return new WasteCategoryVO(
                domain.getId(),
                domain.getCategoryCode(),
                domain.getName(),
                domain.getHazardType() == null ? null : domain.getHazardType().name(),
                domain.getCrossProvince(),
                domain.getStatus() == null ? null : domain.getStatus().name(),
                domain.getCreateTime());
    }

    public static CategoryImpactVO toImpactVo(CategoryImpact impact) {
        return new CategoryImpactVO(
                impact.categoryCode(),
                impact.inStockBatchCount(),
                impact.inStockWeight(),
                impact.openManifestCount(),
                impact.pendingPlanCount(),
                impact.blocksDisable(),
                impact.describe());
    }

    public static DisableCategoryVO toDisableVo(DisableResult result) {
        return new DisableCategoryVO(toVo(result.category()), toImpactVo(result.impact()));
    }

    public static PageVO<WasteCategoryVO> toPageVo(PageResult<WasteCategory> page) {
        List<WasteCategoryVO> content = page.content().stream()
                .map(WasteCategoryVoConverter::toVo)
                .collect(Collectors.toList());
        return new PageVO<>(content, page.total(), page.pageNum(), page.pageSize(), page.totalPages());
    }
}
