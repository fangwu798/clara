package com.clara.skill.api;

import com.clara.kernel.InvocationContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封装技能执行操作的输入参数。
 */
public final class SkillExecutionRequest {
    private final InvocationContext context;
    private final String skillId, version;
    private final Map<String,Object> inputs;
    public SkillExecutionRequest(InvocationContext context,String skillId,String version,Map<String,Object> inputs){
        if(context==null||blank(skillId)||blank(version)) {throw new IllegalArgumentException("skill request is invalid"); }
        this.context=context;this.skillId=skillId;this.version=version;
        this.inputs=inputs==null?Collections.<String,Object>emptyMap():Collections.unmodifiableMap(new LinkedHashMap<String,Object>(inputs));
    }
    public InvocationContext getContext(){return context;} public String getSkillId(){return skillId;}
    public String getVersion(){return version;} public Map<String,Object> getInputs(){return inputs;}
    private static boolean blank(String v){return v==null||v.trim().isEmpty();}
}
