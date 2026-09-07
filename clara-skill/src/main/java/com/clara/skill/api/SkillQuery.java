package com.clara.skill.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 定义技能的查询条件。
 */
public final class SkillQuery {
    private final String domain;
    private final SkillExecutionType executionType;
    private final SkillLifecycle lifecycle;
    private final Set<String> requiredTags;
    public SkillQuery(String domain, SkillExecutionType executionType,
            SkillLifecycle lifecycle, Set<String> requiredTags) {
        this.domain=domain; this.executionType=executionType; this.lifecycle=lifecycle;
        this.requiredTags=requiredTags==null?Collections.<String>emptySet():
                Collections.unmodifiableSet(new LinkedHashSet<String>(requiredTags));
    }
    public String getDomain(){return domain;} public SkillExecutionType getExecutionType(){return executionType;}
    public SkillLifecycle getLifecycle(){return lifecycle;} public Set<String> getRequiredTags(){return requiredTags;}
}
