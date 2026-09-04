package com.clara.skill.runtime;

import com.clara.skill.api.SkillDefinition;
import com.clara.skill.api.SkillHealth;

public interface SkillHealthResolver {
    SkillHealth health(SkillDefinition definition);
}
