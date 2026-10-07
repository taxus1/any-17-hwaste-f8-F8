package com.somepro.domain.hwaste.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.regex.Pattern;

/**
 * 危废类别名录（聚合根，纯领域对象，无框架注解）。
 *
 * 一条名录一个类别：类别代码形如 HW08，一个代码只归一个类别；名录上留类别名称、
 * 危险特性（五档）与跨省标志（1 不许跨省转移 / 0 允许）。没写状态按启用 ENABLED 落。
 *
 * 停用牵动在途业务：入库、新联单、新计划都只认启用的类别；已在办的老单子照走，
 * 停用前先看影响面（在库批次、在办联单、待批计划），别把老单子卡死。
 *
 * 注意：跨省标志是开联单那一刻抄到联单上的快照值，名录后改只影响新开的联单，
 * 老联单上当初抄下来的那个值原样不动（对账两边都平）。
 */
@Getter
@Setter
public class WasteCategory extends BaseEntity {

    /** 类别代码形如 HW08：HW 前缀 + 两位数字。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("HW\\d{2}");

    private Long id;

    /** 危废类别代码，形如 HW08，全局唯一，一个代码只归一个类别。 */
    private String categoryCode;

    /** 类别名称。 */
    private String name;

    /** 危险特性，五档之一。 */
    private HazardType hazardType;

    /** 跨省标志：1 限制（不得跨省转移）/ 0 允许。 */
    private Integer crossProvince;

    private CategoryStatus status;

    /**
     * 新名录录入：校验入参，没写状态按启用 ENABLED 落，跨省标志没写按 0（允许）落。
     */
    public static WasteCategory create(String categoryCode, String name, HazardType hazardType,
                                       Integer crossProvince) {
        WasteCategory category = new WasteCategory();
        category.setCategoryCode(categoryCode);
        category.setName(name);
        category.setHazardType(hazardType);
        category.setCrossProvince(crossProvince);
        category.status = CategoryStatus.ENABLED;
        return category;
    }

    /** 改名录：只动类别名称、危险特性、跨省标志；代码与状态不由此处改。 */
    public void edit(String name, HazardType hazardType, Integer crossProvince) {
        setName(name);
        setHazardType(hazardType);
        setCrossProvince(crossProvince);
    }

    /** 停用：ENABLE → DISABLED；条件更新（WHERE status='ENABLED'）在仓储侧兜底并发。 */
    public void disable() {
        this.status = CategoryStatus.DISABLED;
    }

    /** 只有启用（ENABLED）的类别才允许新入库、开新联单、报新计划。 */
    public boolean isEnabled() {
        return this.status == CategoryStatus.ENABLED;
    }

    /** 名录上标了不许跨省的类别，供废与收货两头不在同一个省时联单开不出去。 */
    public boolean isCrossProvinceRestricted() {
        return this.crossProvince != null && this.crossProvince == 1;
    }

    /** 设置类别代码（转换器回填用；业务录入走 {@link #create}，格式在此兜底）。 */
    public void setCategoryCode(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            throw new BizException("类别代码不能为空");
        }
        String code = categoryCode.trim().toUpperCase();
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new BizException("类别代码格式不正确，应为 HW 加两位数字，如 HW08");
        }
        this.categoryCode = code;
    }

    public void setName(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("类别名称不能为空");
        }
        this.name = name.trim();
    }

    public void setHazardType(HazardType hazardType) {
        if (hazardType == null) {
            throw new BizException("危险特性不能为空");
        }
        this.hazardType = hazardType;
    }

    /** 设置跨省标志（转换器回填用）：只认 0 / 1，空值按 0（允许）。 */
    public void setCrossProvince(Integer crossProvince) {
        if (crossProvince == null) {
            this.crossProvince = 0;
            return;
        }
        if (crossProvince != 0 && crossProvince != 1) {
            throw new BizException("跨省标志只能取 1（限制跨省）或 0（允许）");
        }
        this.crossProvince = crossProvince;
    }
}
