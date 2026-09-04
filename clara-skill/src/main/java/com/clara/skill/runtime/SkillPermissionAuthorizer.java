package com.clara.skill.runtime;

import com.clara.kernel.InvocationContext;
import com.clara.skill.api.SkillDefinition;

public interface SkillPermissionAuthorizer {
    boolean isAllowed(InvocationContext context,SkillDefinition definition);
}
