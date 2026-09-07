package com.clara.skill.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封装技能执行操作的执行结果。
 */
public final class SkillExecutionResult {
    private final String skillId,version,instanceId;
    private final SkillExecutionStatus status;
    private final Map<String,Object> outputs;
    private final long instanceVersion;
    public SkillExecutionResult(String skillId,String version,String instanceId,SkillExecutionStatus status,
            Map<String,Object> outputs,long instanceVersion){
        if(skillId==null||version==null||status==null||instanceVersion<0L) {throw new IllegalArgumentException("skill result is invalid"); }
        this.skillId=skillId;this.version=version;this.instanceId=instanceId;this.status=status;
        this.outputs=outputs==null?Collections.<String,Object>emptyMap():Collections.unmodifiableMap(new LinkedHashMap<String,Object>(outputs));
        this.instanceVersion=instanceVersion;
    }
    public String getSkillId(){return skillId;} public String getVersion(){return version;}
    public String getInstanceId(){return instanceId;} public SkillExecutionStatus getStatus(){return status;}
    public Map<String,Object> getOutputs(){return outputs;} public long getInstanceVersion(){return instanceVersion;}
}
