package com.somepro.domain.hwaste.repository;

import com.somepro.domain.hwaste.model.SourceStatus;
import com.somepro.domain.hwaste.model.WasteSource;
import com.somepro.domain.shared.model.PageResult;
import reactor.core.publisher.Mono;

/**
 * 产废单位仓储端口：领域层定义，基础设施层实现。
 */
public interface WasteSourceRepository {

    /** 按 id 查产废单位；不存在返回空。 */
    Mono<WasteSource> findById(Long id);

    /** 按单位编号查产废单位；不存在返回空。 */
    Mono<WasteSource> findBySourceNo(String sourceNo);

    /**
     * 停用：ACTIVE → SUSPENDED。已经停用时幂等返回当前单位；并发一停一复由行锁串行裁决。
     */
    Mono<WasteSource> suspend(Long id);

    /**
     * 恢复：SUSPENDED → ACTIVE。已经正常时幂等返回当前单位；并发一停一复由行锁串行裁决。
     */
    Mono<WasteSource> resume(Long id);

    /** 按状态分页；关闭单位不纳入本清单。status 为空时只列 ACTIVE / SUSPENDED。 */
    Mono<PageResult<WasteSource>> page(int pageNum, int pageSize, SourceStatus status);
}
