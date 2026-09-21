//package com.clara.skill.runtime;
//
//import com.clara.kernel.exception.ErrorCategory;
//import com.clara.kernel.exception.ErrorCodes;
//import com.clara.skill.api.SkillArtifactVerifier;
//import com.clara.skill.api.SkillDefinition;
//import com.clara.skill.api.SkillLifecycle;
//import com.clara.skill.api.SkillQuery;
//import com.clara.skill.api.SkillRef;
//import com.clara.skill.api.SkillRegistry;
//import com.clara.skill.api.SkillSummary;
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.Comparator;
//import java.util.HashSet;
//import java.util.LinkedHashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.Set;
//
///**
// * 提供技能注册表的内存实现。
// */
//public final class InMemorySkillRegistry implements SkillRegistry {
//    private final SkillArtifactVerifier verifier;
//    private final Map<String, SkillDefinition> definitions = new LinkedHashMap<String, SkillDefinition>();
//    public InMemorySkillRegistry(SkillArtifactVerifier verifier) {
//        if (verifier == null) { throw new IllegalArgumentException("verifier must not be null"); }
//        this.verifier=verifier;
//    }
//    public synchronized SkillDefinition get(String skillId, String version) {
//        SkillDefinition value=definitions.get(key(skillId,version));
//        if(value==null) {throw SkillErrors.error(ErrorCodes.SKILL_NOT_FOUND, ErrorCategory.VALIDATION); } return value;
//    }
//    public synchronized List<SkillSummary> search(SkillQuery query) {
//        if(query==null) {throw new IllegalArgumentException("query must not be null"); }
//        List<SkillSummary> result=new ArrayList<SkillSummary>();
//        for(SkillDefinition value:definitions.values()){
//            if(query.getDomain()!=null&&!query.getDomain().equals(value.getDomain()))continue;
//            if(query.getExecutionType()!=null&&query.getExecutionType()!=value.getExecutionType())continue;
//            if(query.getLifecycle()!=null&&query.getLifecycle()!=value.getLifecycle())continue;
//            if(!value.getTags().containsAll(query.getRequiredTags()))continue;
//            result.add(new SkillSummary(value));
//        }
//        Collections.sort(result,new Comparator<SkillSummary>(){public int compare(SkillSummary a,SkillSummary b){
//            int id=a.getSkillId().compareTo(b.getSkillId()); return id!=0?id:a.getVersion().compareTo(b.getVersion());}});
//        return Collections.unmodifiableList(result);
//    }
//    public synchronized SkillDefinition registerDraft(SkillDefinition value) {
//        if(value==null||value.getLifecycle()!=SkillLifecycle.DRAFT)lifecycle();
//        if(definitions.containsKey(value.key())) {throw SkillErrors.error(ErrorCodes.SKILL_VERSION_EXISTS,ErrorCategory.CONFLICT); }
//        definitions.put(value.key(),value);return value;
//    }
//    public synchronized SkillDefinition review(String id,String version){return transition(id,version,SkillLifecycle.DRAFT,SkillLifecycle.REVIEWED);}
//    public synchronized SkillDefinition publish(String id,String version){
//        SkillDefinition value=get(id,version); if(value.getLifecycle()!=SkillLifecycle.REVIEWED)lifecycle();
//        if(!verifier.verify(value)) {throw SkillErrors.error(ErrorCodes.SKILL_ARTIFACT_REJECTED,ErrorCategory.CONTENT_SAFETY); }
//        validateDependencies(value); return replace(value,SkillLifecycle.PUBLISHED);
//    }
//    public synchronized SkillDefinition deprecate(String id,String version){return transition(id,version,SkillLifecycle.PUBLISHED,SkillLifecycle.DEPRECATED);}
//    public synchronized SkillDefinition retire(String id,String version){return transition(id,version,SkillLifecycle.DEPRECATED,SkillLifecycle.RETIRED);}
//    private SkillDefinition transition(String id,String version,SkillLifecycle from,SkillLifecycle to){
//        SkillDefinition value=get(id,version);if(value.getLifecycle()!=from)lifecycle();return replace(value,to);
//    }
//    private SkillDefinition replace(SkillDefinition value,SkillLifecycle lifecycle){
//        SkillDefinition changed=value.withLifecycle(lifecycle);definitions.put(changed.key(),changed);return changed;
//    }
//    private void validateDependencies(SkillDefinition candidate){
//        for(SkillRef ref:candidate.getDependencies()){
//            SkillDefinition dependency=definitions.get(ref.key());
//            if(dependency==null||(dependency.getLifecycle()!=SkillLifecycle.PUBLISHED&&dependency.getLifecycle()!=SkillLifecycle.DEPRECATED))dependency();
//        }
//        visit(candidate,candidate.key(),new HashSet<String>());
//    }
//    private void visit(SkillDefinition value,String target,Set<String> path){
//        if(!path.add(value.key())||value.key().equals(target)&&path.size()>1)dependency();
//        for(SkillRef ref:value.getDependencies()){
//            if(ref.key().equals(target))dependency();
//            SkillDefinition child=definitions.get(ref.key()); if(child!=null)visit(child,target,path);
//        }
//        path.remove(value.key());
//    }
//    private static String key(String id,String version){
//        if(id==null||id.trim().isEmpty()||version==null||version.trim().isEmpty()) {throw new IllegalArgumentException("skill identity is invalid"); }
//        return id+":"+version;
//    }
//    private static void lifecycle(){throw SkillErrors.error(ErrorCodes.SKILL_INVALID_LIFECYCLE,ErrorCategory.CONFLICT);}
//    private static void dependency(){throw SkillErrors.error(ErrorCodes.SKILL_DEPENDENCY_INVALID,ErrorCategory.VALIDATION);}
//}
