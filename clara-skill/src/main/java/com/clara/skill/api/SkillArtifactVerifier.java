package com.clara.skill.api;

/**
 * 验证技能制品的真实性或完整性。
 */
public interface SkillArtifactVerifier {
    boolean verify(SkillDefinition definition);
}
