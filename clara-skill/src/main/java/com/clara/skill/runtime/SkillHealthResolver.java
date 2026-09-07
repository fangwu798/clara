package com.clara.skill.runtime;

import com.clara.skill.api.SkillDefinition;
import com.clara.skill.api.SkillHealth;

/**
 * 解析并提供技能健康状态。
 */
public interface SkillHealthResolver {
    SkillHealth health(SkillDefinition definition);
}
