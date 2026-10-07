package com.somepro.domain.hwaste.model;

import java.math.BigDecimal;

/**
 * 类别停用前的影响面（领域值对象，不可变）。
 *
 * 把这个类别当前牵动的三件事一起列出来：
 * - 在库批次：还压在库里的批次数与重量合计（千克）；
 * - 在办联单：还没走完的联单张数（已退回 / 已作废 / 已处置的不算）；
 * - 待批计划：还没批下来的计划份数（草稿 / 已申报）。
 *
 * 有在库批次或在办联单时，停用会牵动这些单子，调用方必须给用户明白提示；
 * 待批计划一并展示，但本身不构成硬挡（停用后它照常在办流程里走，只是新计划不再收）。
 */
public record CategoryImpact(Long categoryId,
                             String categoryCode,
                             long inStockBatchCount,
                             BigDecimal inStockWeight,
                             long openManifestCount,
                             long pendingPlanCount) {

    public CategoryImpact {
        inStockWeight = inStockWeight == null ? BigDecimal.ZERO : inStockWeight;
        inStockBatchCount = Math.max(inStockBatchCount, 0);
        openManifestCount = Math.max(openManifestCount, 0);
        pendingPlanCount = Math.max(pendingPlanCount, 0);
    }

    /** 有在库批次或在办联单：停用会卡住 / 牵动在途业务，要给明白提示（或要求显式强制停用）。 */
    public boolean blocksDisable() {
        return inStockBatchCount > 0 || openManifestCount > 0;
    }

    /** 给人看的影响面文案；无影响时为空串。 */
    public String describe() {
        if (inStockBatchCount == 0 && openManifestCount == 0 && pendingPlanCount == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("停用后该类别不再接收新入库、新联单、新计划，已在办的单子照走。");
        sb.append("当前影响面：在库批次 ").append(inStockBatchCount).append(" 批")
                .append("、在库重量 ").append(inStockWeight.stripTrailingZeros().toPlainString()).append(" 千克");
        if (openManifestCount > 0) {
            sb.append("；未走完联单 ").append(openManifestCount).append(" 张");
        }
        if (pendingPlanCount > 0) {
            sb.append("；待批计划 ").append(pendingPlanCount).append(" 份");
        }
        return sb.toString();
    }
}
