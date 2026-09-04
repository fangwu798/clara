package com.clara.skill.api;

public final class SkillRef {
    private final String skillId;
    private final String version;
    public SkillRef(String skillId, String version) {
        if (blank(skillId) || blank(version)) throw new IllegalArgumentException("skill reference is invalid");
        this.skillId = skillId; this.version = version;
    }
    public String getSkillId() { return skillId; }
    public String getVersion() { return version; }
    public String key() { return skillId + ":" + version; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
