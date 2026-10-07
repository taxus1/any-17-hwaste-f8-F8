package com.somepro.interfaces.rest.hwaste.vo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 停用前影响面对外返回对象（VO，用户接口层）—— 不可变 record。
 * 每项把类别代码回出来，计数与重量都是停用这一刻在锁内取的快照。
 */
public record CategoryImpactVO(
        String categoryCode,
        long inStockBatchCount,
        BigDecimal inStockWeight,
        long openManifestCount,
        long pendingPlanCount,
        boolean blocksDisable,
        String notice) implements Serializable {
}
