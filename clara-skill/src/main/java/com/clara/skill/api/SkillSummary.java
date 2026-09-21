//package com.clara.skill.api;
//
//import java.util.Set;
//
///**
// * 表示。
// */
//public final class SkillSummary {
//    private final String skillId, name, version, domain;
//    private final SkillExecutionType executionType;
//    private final SkillLifecycle lifecycle;
//    private final Set<String> tags;
//    public SkillSummary(SkillDefinition value) {
//        skillId=value.getSkillId(); name=value.getName(); version=value.getVersion(); domain=value.getDomain();
//        executionType=value.getExecutionType(); lifecycle=value.getLifecycle(); tags=value.getTags();
//    }
//    public String getSkillId(){return skillId;} public String getName(){return name;}
//    public String getVersion(){return version;} public String getDomain(){return domain;}
//    public SkillExecutionType getExecutionType(){return executionType;}
//    public SkillLifecycle getLifecycle(){return lifecycle;} public Set<String> getTags(){return tags;}
//}
