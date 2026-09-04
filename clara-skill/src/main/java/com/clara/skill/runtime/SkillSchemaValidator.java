package com.clara.skill.runtime;

import java.util.Map;

public interface SkillSchemaValidator {
    boolean isValid(String schema,Map<String,Object> value);
}
