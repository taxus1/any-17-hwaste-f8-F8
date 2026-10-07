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
 * 单位停产、搬迁、被要求整治时先停用把它「摁住」：ACTIVE → SUSPENDED，
 * 谁办的、什么时候办的随状态一起落审计；整治完恢复 SUSPENDED → ACTIVE。
 *
 * 两笔都允许重复按：已停用再停、已正常再恢复都原样返回，连按两回状态不跳。
 * 一停一复几乎同时到达时，最终结果由仓储侧单位行锁 + 条件更新裁决，落库只有一个状态。
 *
 * 停用的连带（校验在各开新单的用例里，裁决在仓储行锁里）：
 * 名下不再登新入库批次、名下年度计划不再开出新联单；已经开出去在途的联单照走
 * （签收 / 处置确认不看单位状态），不会被一起卡住。
 */
@Service
public class WasteSourceAppService {

    private final WasteSourceRepository wasteSourceRepository;

    public WasteSourceAppService(WasteSourceRepository wasteSourceRepository) {
        this.wasteSourceRepository = wasteSourceRepository;
    }

    /** 停用：正常 → 停用；已停用再停幂等返回，已关闭挡回。办理人 / 时刻随审计落库。 */
    public Mono<WasteSource> suspend(Long id, String sourceNo) {
        return loadId(id, sourceNo).flatMap(wasteSourceRepository::suspend);
    }

    /** 恢复：停用 → 正常；已正常再恢复幂等返回，已关闭挡回。 */
    public Mono<WasteSource> resume(Long id, String sourceNo) {
        return loadId(id, sourceNo).flatMap(wasteSourceRepository::resume);
    }

    /**
     * 按状态看单位清单，分页往下翻，每行带单位编号。
     * 状态取值沿用单位档案（ACTIVE / SUSPENDED / CLOSED）；已关闭的单位不在这个清单里，
     * 显式传 CLOSED 也是空页。不传状态则列出正常 + 停用两档。
     */
    public Mono<PageResult<WasteSource>> page(int pageNum, int pageSize, String status) {
        return wasteSourceRepository.page(pageNum, pageSize, parseStatusOrNull(status));
    }

    /** 按 id 或单位编号解析单位 id；两个都不传或查不到都视为业务失败。 */
    private Mono<Long> loadId(Long id, String sourceNo) {
        if (id != null) {
            return wasteSourceRepository.findById(id)
                    .switchIfEmpty(Mono.error(new BizException("产废单位不存在")))
                    .map(WasteSource::getId);
        }
        if (sourceNo != null && !sourceNo.isBlank()) {
            return wasteSourceRepository.findBySourceNo(sourceNo.trim())
                    .switchIfEmpty(Mono.error(new BizException("产废单位不存在")))
                    .map(WasteSource::getId);
        }
        return Mono.error(new BizException("id 或 sourceNo 必传其一"));
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
