package com.somepro.domain.hwaste.model;

/**
 * 危险特性（纯领域枚举，落库时存 name() 字符串）。名录上每个类别挂一档，共五档。
 */
public enum HazardType {

    /** 毒性。 */
    TOXIC,
    /** 腐蚀性。 */
    CORROSIVE,
    /** 易燃。 */
    FLAMMABLE,
    /** 反应性。 */
    REACTIVE,
    /** 感染性。 */
    INFECTIOUS
}
