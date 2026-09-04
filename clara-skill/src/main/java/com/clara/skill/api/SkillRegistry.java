package com.clara.skill.api;

import java.util.List;

public interface SkillRegistry {
    SkillDefinition get(String skillId, String version);
    List<SkillSummary> search(SkillQuery query);
    SkillDefinition registerDraft(SkillDefinition definition);
    SkillDefinition review(String skillId, String version);
    SkillDefinition publish(String skillId, String version);
    SkillDefinition deprecate(String skillId, String version);
    SkillDefinition retire(String skillId, String version);
}
