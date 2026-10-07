package com.somepro.interfaces.rest.hwaste.vo;

import java.io.Serializable;

/**
 * 停用结果对外返回对象（VO，用户接口层）—— 不可变 record。
 * 名录停用后的状态与停用那一刻的影响面快照一并带回，方便前端落账展示。
 */
public record DisableCategoryVO(WasteCategoryVO category, CategoryImpactVO impact) implements Serializable {
}
