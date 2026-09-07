package com.clara.skill.runtime;

import com.clara.kernel.InvocationContext;
import com.clara.skill.api.SkillDefinition;

/**
 * 校验对技能权限的访问权限。
 */
public interface SkillPermissionAuthorizer {
    boolean isAllowed(InvocationContext context,SkillDefinition definition);
}
