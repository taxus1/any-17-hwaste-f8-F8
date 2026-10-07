package com.somepro.domain.hwaste.repository;

import com.somepro.domain.hwaste.model.SourceStatus;
import com.somepro.domain.hwaste.model.WasteSource;
import com.somepro.domain.shared.model.PageResult;
import reactor.core.publisher.Mono;

/**
 * 产废单位仓储端口：领域层定义，基础设施层实现。
 *
 * 入库登记、开新联单据此校验单位状态；停用 / 恢复的并发裁决也落在仓储侧
 * （事务内锁单位行 + 条件更新）。
 */
public interface WasteSourceRepository {

    /** 按 id 查产废单位；不存在返回空。 */
    Mono<WasteSource> findById(Long id);

    /** 按单位编号查产废单位；不存在返回空。 */
    Mono<WasteSource> findBySourceNo(String sourceNo);

    /**
     * 按状态分页看单位清单。已关闭（CLOSED）的单位不进这个清单；
     * status 传 null 表示只把 ACTIVE / SUSPENDED 两档都列出来（仍不含 CLOSED）。
     */
    Mono<PageResult<WasteSource>> page(int pageNum, int pageSize, SourceStatus status);

    /**
     * 停用（ACTIVE → SUSPENDED），事务内锁单位行后条件更新，与开新单 / 恢复并发互斥。
     * 已停用再停是幂等空操作；已关闭挡回。
     */
    Mono<WasteSource> suspend(Long id);

    /**
     * 恢复（SUSPENDED → ACTIVE），事务内锁单位行后条件更新，与停用并发互斥。
     * 已正常再恢复是幂等空操作；已关闭挡回。
     */
    Mono<WasteSource> resume(Long id);
}
