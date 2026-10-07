package com.somepro.domain.hwaste.repository;

import com.somepro.domain.hwaste.model.CategoryImpact;
import com.somepro.domain.hwaste.model.CategoryStatus;
import com.somepro.domain.hwaste.model.DisableResult;
import com.somepro.domain.hwaste.model.HazardType;
import com.somepro.domain.hwaste.model.WasteCategory;
import com.somepro.domain.shared.model.PageResult;
import reactor.core.publisher.Mono;

/**
 * 危废类别仓储端口：领域层定义，基础设施层实现。
 *
 * 既是名录 CRUD 的入口，也供入库登记 / 联单 / 计划校验类别状态。
 * 停用与影响面取数必须在同一把行锁里完成（见 {@link #disable}），
 * 保证「停用」与「开新单」两件事几乎同时发生时账是自洽的。
 */
public interface WasteCategoryRepository {

    /** 按类别代码查危废类别；不存在返回空。 */
    Mono<WasteCategory> findByCode(String categoryCode);

    /** 按 id 查危废类别；不存在返回空。 */
    Mono<WasteCategory> findById(Long id);

    /** 名录翻页：危险特性、状态均可选，一个不填分页列全；每条都带类别代码。 */
    Mono<PageResult<WasteCategory>> page(int pageNum, int pageSize, HazardType hazardType, CategoryStatus status);

    /** 新录入名录；重复代码一律挡回（应用层查重 + 库表唯一约束兜底）。 */
    Mono<WasteCategory> create(WasteCategory category);

    /** 改名录：只动类别名称、危险特性、跨省标志；代码与状态不动。 */
    Mono<WasteCategory> update(WasteCategory category);

    /** 停用前影响面：在库批次数与重量、未走完联单张数、待批计划份数。 */
    Mono<CategoryImpact> assessImpact(String categoryCode);

    /**
     * 停用：锁类别行 → 取影响面快照 → 条件更新 ENABLED → DISABLED，一段事务内完成。
     *
     * 影响面里有在库批次或在办联单且 force=false 时拒绝停用（影响面文案随异常给出）；
     * force=true 表示用户已看清影响面、确认强制停用。已停用的重复停用直接报业务异常。
     *
     * 与「开新联单 / 新入库 / 新计划」共用类别行锁：后到者在锁里看到的要么是停用前
     * （单子照开，停用随后生效）、要么是已停用（单子被挡回），不会两头对不上。
     */
    Mono<DisableResult> disable(String categoryCode, boolean force);
}
