package com.somepro.domain.hwaste.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 产废单位（纯领域聚合根）。
 *
 * 状态沿用单位档案那套 {@link SourceStatus}：ACTIVE 正常 / SUSPENDED 停用 / CLOSED 关闭。
 * 单位停产、搬迁、被要求整治时先停用 —— 停用只挡「新开的业务」（新入库批次、名下计划开新联单），
 * 已经开出去、还在路上的联单不受影响，该签收签收、该处置确认确认。
 *
 * 停用 / 恢复都允许重复按：处在目标态再按一下是空操作，连按两回状态不会来回跳。
 * CLOSED 是终态：关闭的单位既不参与清单查询，也不允许停用 / 恢复。
 * 「谁办的、什么时候办的」记在审计列 updateBy / updateTime 上（MetaObjectHandler 自动填充）。
 */
@Getter
@Setter
public class WasteSource extends BaseEntity {

    private Long id;

    /** 产废单位编号，形如 WS-2026-0001，全局唯一。 */
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

    /** 只有正常（ACTIVE）的单位才允许登记入库、用名下计划开新联单。 */
    public boolean isActive() {
        return this.status == SourceStatus.ACTIVE;
    }

    /**
     * 停用：ACTIVE → SUSPENDED。
     * 已停用再停是幂等空操作（状态不跳、审计不重写）；已关闭的单位不允许停用。
     */
    public void suspend() {
        if (this.status == SourceStatus.CLOSED) {
            throw new BizException("产废单位已关闭，不能停用");
        }
        if (this.status == SourceStatus.SUSPENDED) {
            return;
        }
        this.status = SourceStatus.SUSPENDED;
    }

    /**
     * 恢复：SUSPENDED → ACTIVE。
     * 已正常再恢复是幂等空操作；已关闭的单位不允许恢复。
     */
    public void resume() {
        if (this.status == SourceStatus.CLOSED) {
            throw new BizException("产废单位已关闭，不能恢复");
        }
        if (this.status == SourceStatus.ACTIVE) {
            return;
        }
        this.status = SourceStatus.ACTIVE;
    }

    /** 状态最近一次变更（停用 / 恢复）的办理人，取审计列 updateBy。 */
    public String getStatusChangedBy() {
        return getUpdateBy();
    }

    /** 状态最近一次变更（停用 / 恢复）的办理时刻，取审计列 updateTime。 */
    public LocalDateTime getStatusChangedAt() {
        return getUpdateTime();
    }
}
