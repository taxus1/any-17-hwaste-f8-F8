package com.somepro.domain.hwaste.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 产废单位（纯领域聚合根）。
 *
 * 停用 / 恢复只在 ACTIVE 与 SUSPENDED 之间切换：重复操作是幂等空操作，
 * CLOSED 是终态，不能再停也不能再恢复。停用只挡新入库和新开联单，
 * 已经开出去的在途联单仍按原流程签收、处置确认。
 */
@Getter
@Setter
public class WasteSource extends BaseEntity {

    private Long id;

    /** 产废单位编号，形如 WS-2026-0001。 */
    private String sourceNo;

    private String name;

    /** 统一社会信用代码。 */
    private String creditCode;

    /** 所在省份（联单跨省判定用）。 */
    private String province;

    private String city;

    private String address;

    private String contact;

    private String phone;

    private SourceStatus status;

    /**
     * 停用：正常 → 停用；已经停用时幂等返回，不重复改状态；关闭单位不能停用。
     * 仓储侧用行锁和条件更新兜底并发，同一单位一停一复最终只留下后提交的结果。
     */
    public void suspend() {
        if (status == SourceStatus.SUSPENDED) {
            return;
        }
        if (status == SourceStatus.CLOSED) {
            throw new BizException("产废单位已关闭，不能停用");
        }
        this.status = SourceStatus.SUSPENDED;
    }

    /**
     * 恢复：停用 → 正常；已经正常时幂等返回，不重复改状态；关闭单位不能恢复。
     * 仓储侧用行锁和条件更新兜底并发，避免旧状态覆盖新状态。
     */
    public void resume() {
        if (status == SourceStatus.ACTIVE) {
            return;
        }
        if (status == SourceStatus.CLOSED) {
            throw new BizException("产废单位已关闭，不能恢复");
        }
        this.status = SourceStatus.ACTIVE;
    }

    /** 只有正常（ACTIVE）的单位才允许登记入库、开具新联单。 */
    public boolean isActive() {
        return this.status == SourceStatus.ACTIVE;
    }
}
