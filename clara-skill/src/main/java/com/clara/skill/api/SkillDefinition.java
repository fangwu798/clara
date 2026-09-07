package com.clara.skill.api;

import com.clara.model.api.ModelCapability;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定义技能的结构与元数据。
 */
public final class SkillDefinition {
    private static final int SCHEMA_VERSION = 1;
    private final String skillId, name, version, domain, description;
    private final SkillExecutionType executionType;
    private final String executionRef, inputSchema, outputSchema, instruction, permissionPolicy;
    private final List<String> resources;
    private final Set<String> requiredTools;
    private final Set<ModelCapability> requiredModelCapabilities;
    private final List<SkillRef> dependencies;
    private final long timeoutMillis;
    private final Set<String> tags;
    private final SkillArtifact artifact;
    private final SkillLifecycle lifecycle;

    private SkillDefinition(Builder b) {
        if (blank(b.skillId) || blank(b.name) || blank(b.version) || blank(b.domain)
                || blank(b.description) || b.executionType == null || blank(b.executionRef)
                || blank(b.inputSchema) || blank(b.outputSchema) || blank(b.permissionPolicy)
                || b.timeoutMillis <= 0L || b.artifact == null || b.lifecycle == null) {
            throw new IllegalArgumentException("skill definition is invalid");
        }
        skillId=b.skillId; name=b.name; version=b.version; domain=b.domain; description=b.description;
        executionType=b.executionType; executionRef=b.executionRef; inputSchema=b.inputSchema;
        outputSchema=b.outputSchema; instruction=b.instruction; permissionPolicy=b.permissionPolicy;
        resources=list(b.resources); requiredTools=set(b.requiredTools);
        requiredModelCapabilities=Collections.unmodifiableSet(new LinkedHashSet<ModelCapability>(b.requiredModelCapabilities));
        dependencies=Collections.unmodifiableList(new ArrayList<SkillRef>(b.dependencies));
        timeoutMillis=b.timeoutMillis; tags=set(b.tags); artifact=b.artifact; lifecycle=b.lifecycle;
        validateStrings(resources,"resource"); validateStrings(requiredTools,"required tool");
        validateStrings(tags,"tag");
        Set<String> dependencyKeys=new LinkedHashSet<String>();
        for(SkillRef dependency:dependencies)if(dependency==null||!dependencyKeys.add(dependency.key())) {
            throw new IllegalArgumentException("skill dependencies are invalid");
        }
    }
    public static Builder builder() { return new Builder(); }
    public int getSchemaVersion(){return SCHEMA_VERSION;} public String getSkillId(){return skillId;}
    public String getName(){return name;} public String getVersion(){return version;}
    public String getDomain(){return domain;} public String getDescription(){return description;}
    public SkillExecutionType getExecutionType(){return executionType;} public String getExecutionRef(){return executionRef;}
    public String getInputSchema(){return inputSchema;} public String getOutputSchema(){return outputSchema;}
    public String getInstruction(){return instruction;} public String getPermissionPolicy(){return permissionPolicy;}
    public List<String> getResources(){return resources;} public Set<String> getRequiredTools(){return requiredTools;}
    public Set<ModelCapability> getRequiredModelCapabilities(){return requiredModelCapabilities;}
    public List<SkillRef> getDependencies(){return dependencies;} public long getTimeoutMillis(){return timeoutMillis;}
    public Set<String> getTags(){return tags;} public SkillArtifact getArtifact(){return artifact;}
    public SkillLifecycle getLifecycle(){return lifecycle;} public String key(){return skillId+":"+version;}
    public SkillDefinition withLifecycle(SkillLifecycle value) {
        return builder().from(this).lifecycle(value).build();
    }
    private static List<String> list(List<String> v){return Collections.unmodifiableList(new ArrayList<String>(v));}
    private static Set<String> set(Set<String> v){return Collections.unmodifiableSet(new LinkedHashSet<String>(v));}
    private static boolean blank(String v){return v==null||v.trim().isEmpty();}
    private static void validateStrings(Iterable<String> values,String name){for(String value:values)if(blank(value)) {
        throw new IllegalArgumentException(name+" must not be blank");
    }}
    /**
     * 表示。
     */
    public static final class Builder {
        private String skillId,name,version,domain,description,executionRef,inputSchema,outputSchema,instruction,permissionPolicy;
        private SkillExecutionType executionType; private List<String> resources=Collections.emptyList();
        private Set<String> requiredTools=Collections.emptySet();
        private Set<ModelCapability> requiredModelCapabilities=Collections.emptySet();
        private List<SkillRef> dependencies=Collections.emptyList(); private long timeoutMillis=30000L;
        private Set<String> tags=Collections.emptySet(); private SkillArtifact artifact; private SkillLifecycle lifecycle=SkillLifecycle.DRAFT;
        public Builder from(SkillDefinition v){skillId=v.skillId;name=v.name;version=v.version;domain=v.domain;description=v.description;executionType=v.executionType;executionRef=v.executionRef;inputSchema=v.inputSchema;outputSchema=v.outputSchema;instruction=v.instruction;permissionPolicy=v.permissionPolicy;resources=v.resources;requiredTools=v.requiredTools;requiredModelCapabilities=v.requiredModelCapabilities;dependencies=v.dependencies;timeoutMillis=v.timeoutMillis;tags=v.tags;artifact=v.artifact;lifecycle=v.lifecycle;return this;}
        public Builder skillId(String v){skillId=v;return this;} public Builder name(String v){name=v;return this;}
        public Builder version(String v){version=v;return this;} public Builder domain(String v){domain=v;return this;}
        public Builder description(String v){description=v;return this;} public Builder executionType(SkillExecutionType v){executionType=v;return this;}
        public Builder executionRef(String v){executionRef=v;return this;} public Builder inputSchema(String v){inputSchema=v;return this;}
        public Builder outputSchema(String v){outputSchema=v;return this;} public Builder instruction(String v){instruction=v;return this;}
        public Builder permissionPolicy(String v){permissionPolicy=v;return this;} public Builder resources(List<String> v){resources=v==null?Collections.<String>emptyList():v;return this;}
        public Builder requiredTools(Set<String> v){requiredTools=v==null?Collections.<String>emptySet():v;return this;}
        public Builder requiredModelCapabilities(Set<ModelCapability> v){requiredModelCapabilities=v==null?Collections.<ModelCapability>emptySet():v;return this;}
        public Builder dependencies(List<SkillRef> v){dependencies=v==null?Collections.<SkillRef>emptyList():v;return this;}
        public Builder timeoutMillis(long v){timeoutMillis=v;return this;} public Builder tags(Set<String> v){tags=v==null?Collections.<String>emptySet():v;return this;}
        public Builder artifact(SkillArtifact v){artifact=v;return this;} public Builder lifecycle(SkillLifecycle v){lifecycle=v;return this;}
        public SkillDefinition build(){return new SkillDefinition(this);}
    }
}
