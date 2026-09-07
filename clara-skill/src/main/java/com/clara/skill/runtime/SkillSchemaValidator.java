package com.clara.skill.runtime;

import java.util.Map;

/**
 * 校验技能模式是否符合约束。
 */
public interface SkillSchemaValidator {
    boolean isValid(String schema,Map<String,Object> value);
}
