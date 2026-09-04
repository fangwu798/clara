package com.clara.skill.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SkillResumeRequest {
    private final SkillExecutionRequest executionRequest;
    private final String instanceId;
    private final long expectedVersion;
    private final Map<String,Object> feedback;
    public SkillResumeRequest(SkillExecutionRequest request,String instanceId,long expectedVersion,Map<String,Object> feedback){
        if(request==null||instanceId==null||instanceId.trim().isEmpty()||expectedVersion<0L)throw new IllegalArgumentException("resume request is invalid");
        executionRequest=request;this.instanceId=instanceId;this.expectedVersion=expectedVersion;
        this.feedback=feedback==null?Collections.<String,Object>emptyMap():Collections.unmodifiableMap(new LinkedHashMap<String,Object>(feedback));
    }
    public SkillExecutionRequest getExecutionRequest(){return executionRequest;} public String getInstanceId(){return instanceId;}
    public long getExpectedVersion(){return expectedVersion;} public Map<String,Object> getFeedback(){return feedback;}
}
