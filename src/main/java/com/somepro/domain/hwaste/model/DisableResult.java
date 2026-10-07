package com.somepro.domain.hwaste.model;

/**
 * 停用结果（领域值对象，不可变）：停用后的名录 + 停用前在锁内取的影响面快照。
 *
 * 停用与影响面取数在同一个事务 / 行锁里完成，返回的影响面就是这一刀落下那一刻的账，
 * 不会出现「名录已停用、影响面却是旧数」。
 */
public record DisableResult(WasteCategory category, CategoryImpact impact) {
}
