//package com.clara.skill.api;
//
//import com.clara.context.CallContext;
//import com.clara.context.ConvContext;
//import java.util.Collections;
//import java.util.LinkedHashMap;
//import java.util.Map;
//
///**
// * 封装技能执行操作的输入参数。
// */
//public final class SkillExecutionRequest {
//    private final ConvContext conversation;
//    private final CallContext call;
//    private final String runId, instanceId;
//    private final String skillId, version;
//    private final Map<String,Object> inputs;
//    public SkillExecutionRequest(ConvContext conversation, CallContext call, String runId, String instanceId, String skillId, String version, Map<String,Object> inputs){
//        if(conversation==null||call==null||blank(skillId)||blank(version)) {throw new IllegalArgumentException("skill request is invalid"); }
//        this.conversation=conversation;this.call=call;this.runId=runId;this.instanceId=instanceId;
//        this.skillId=skillId;this.version=version;
//        this.inputs=inputs==null?Collections.<String,Object>emptyMap():Collections.unmodifiableMap(new LinkedHashMap<String,Object>(inputs));
//    }
//    public ConvContext getConversation(){return conversation;} public CallContext getCall(){return call;}
//    public String getRunId(){return runId;} public String getInstanceId(){return instanceId;}
//    public String getSkillId(){return skillId;}
//    public String getVersion(){return version;} public Map<String,Object> getInputs(){return inputs;}
//    private static boolean blank(String v){return v==null||v.trim().isEmpty();}
//}
