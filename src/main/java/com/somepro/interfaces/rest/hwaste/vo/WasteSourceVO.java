package com.somepro.interfaces.rest.hwaste.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 产废单位对外返回对象（VO，用户接口层）—— 不可变 record。
 * delFlag 不进 API 契约；停用 / 恢复操作人及时间复用档案审计字段返回。
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
        String createBy,
        LocalDateTime createTime,
        String updateBy,
        LocalDateTime updateTime) implements Serializable {
}
