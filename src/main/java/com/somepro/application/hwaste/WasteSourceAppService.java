package com.somepro.application.hwaste;

import com.somepro.common.exception.BizException;
import com.somepro.domain.hwaste.model.SourceStatus;
import com.somepro.domain.hwaste.model.WasteSource;
import com.somepro.domain.hwaste.repository.WasteSourceRepository;
import com.somepro.domain.shared.model.PageResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 产废单位停用 / 恢复与状态清单用例编排（应用层）。
 *
 * 停用、恢复都允许重复提交：仓储在单位行锁内看到已经是目标状态时直接返回当前档案，
 * 不重复改审计字段。一停一复并发时由数据库行锁和条件状态更新裁决，最终只落一个状态。
 *
 * 停用只阻断新业务：新入库批次、已批年度计划下新开的联单都会挡回；已经在路上的联单
 * 继续审批、启运、签收和处置确认。关闭单位不进入状态清单，也不能停用或恢复。
 */
@Service
public class WasteSourceAppService {

    private final WasteSourceRepository wasteSourceRepository;

    public WasteSourceAppService(WasteSourceRepository wasteSourceRepository) {
        this.wasteSourceRepository = wasteSourceRepository;
    }

    /** 停用：ACTIVE → SUSPENDED；重复停用幂等，关闭单位不能停用。 */
    public Mono<WasteSource> suspend(Long id, String sourceNo) {
        return loadId(id, sourceNo).flatMap(wasteSourceRepository::suspend);
    }

    /** 恢复：SUSPENDED → ACTIVE；重复恢复幂等，关闭单位不能恢复。 */
    public Mono<WasteSource> resume(Long id, String sourceNo) {
        return loadId(id, sourceNo).flatMap(wasteSourceRepository::resume);
    }

    /** 按状态分页查看；不传状态只列正常 / 停用，关闭单位始终排除。 */
    public Mono<PageResult<WasteSource>> page(int pageNum, int pageSize, String status) {
        return wasteSourceRepository.page(pageNum, pageSize, parseStatusOrNull(status));
    }

    /** 按 id 或单位编号解析单位；两个都不传或查不到都视为业务失败。 */
    private Mono<Long> loadId(Long id, String sourceNo) {
        Mono<WasteSource> found;
        if (id != null) {
            found = wasteSourceRepository.findById(id);
        } else if (sourceNo != null && !sourceNo.isBlank()) {
            found = wasteSourceRepository.findBySourceNo(sourceNo.trim());
        } else {
            return Mono.error(new BizException("id 或 sourceNo 必传其一"));
        }
        return found.switchIfEmpty(Mono.error(new BizException("产废单位不存在")))
                .map(WasteSource::getId);
    }

    private static SourceStatus parseStatusOrNull(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SourceStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("状态取值非法，取值：ACTIVE / SUSPENDED / CLOSED");
        }
    }
}
