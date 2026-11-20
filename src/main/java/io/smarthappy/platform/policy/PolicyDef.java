package io.smarthappy.platform.policy;

import lombok.Getter;
import lombok.Setter;

/** sys_policy_def 한 행 */
@Getter
@Setter
public class PolicyDef {
    private String policyKey;
    private String valueType;
    private String defaultValue;
    private String[] allowedValues;
    private Integer minValue;
    private Integer maxValue;
    private String plantScopeYn;
}
