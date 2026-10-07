package com.somepro.interfaces.rest.hwaste.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 产废单位对外返回对象（VO，用户接口层）—— 不可变 record。
 * delFlag / createBy 不进 API 契约；停用 / 恢复的办理人与办理时刻是本模块要看的审计，
 * 由审计列 updateBy / updateTime 透出。
 */
public record WasteSourceVO(
        Long id,
        String sourceNo,
        String name,
        String creditCode,
        String province,
        String city,
        String address,
        String contact,
        String phone,
        String status,
        String statusChangedBy,
        LocalDateTime statusChangedAt,
        LocalDateTime createTime) implements Serializable {
}
