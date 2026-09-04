package com.clara.skill.runtime;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.skill.api.SkillArtifact;
import com.clara.skill.api.SkillArtifactVerifier;
import com.clara.skill.api.SkillDefinition;
import com.clara.skill.api.SkillExecutionType;
import com.clara.skill.api.SkillLifecycle;
import com.clara.skill.api.SkillQuery;
import com.clara.skill.api.SkillRef;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class InMemorySkillRegistryTest {
    @Test
    public void followsLifecycleAndNeverOverwritesVersion() {
        InMemorySkillRegistry registry=registry(true); SkillDefinition draft=definition("one","1",null);
        registry.registerDraft(draft);
        assertEquals(SkillLifecycle.REVIEWED,registry.review("one","1").getLifecycle());
        assertEquals(SkillLifecycle.PUBLISHED,registry.publish("one","1").getLifecycle());
        assertEquals(SkillLifecycle.DEPRECATED,registry.deprecate("one","1").getLifecycle());
        assertEquals(SkillLifecycle.RETIRED,registry.retire("one","1").getLifecycle());
        assertError(ErrorCodes.SKILL_VERSION_EXISTS,new Runnable(){public void run(){registry.registerDraft(draft);}});
        assertError(ErrorCodes.SKILL_INVALID_LIFECYCLE,new Runnable(){public void run(){registry.publish("one","1");}});
    }

    @Test
    public void verifiesArtifactAndRequiresPublishedExactDependencies() {
        final InMemorySkillRegistry rejected=registry(false); rejected.registerDraft(definition("bad","1",null)); rejected.review("bad","1");
        assertError(ErrorCodes.SKILL_ARTIFACT_REJECTED,new Runnable(){public void run(){rejected.publish("bad","1");}});

        InMemorySkillRegistry registry=registry(true);
        SkillDefinition dependency=definition("dependency","1",null);
        registry.registerDraft(dependency);registry.review("dependency","1");registry.publish("dependency","1");
        SkillDefinition consumer=definition("consumer","1",Collections.singletonList(new SkillRef("dependency","1")));
        registry.registerDraft(consumer);registry.review("consumer","1");registry.publish("consumer","1");
        assertEquals(2,registry.search(new SkillQuery(null,null,SkillLifecycle.PUBLISHED,
                Collections.<String>emptySet())).size());

        SkillDefinition missing=definition("missing-user","1",Collections.singletonList(new SkillRef("none","9")));
        registry.registerDraft(missing);registry.review("missing-user","1");
        assertError(ErrorCodes.SKILL_DEPENDENCY_INVALID,new Runnable(){public void run(){registry.publish("missing-user","1");}});
    }

    static InMemorySkillRegistry registry(final boolean accepted){return new InMemorySkillRegistry(new SkillArtifactVerifier(){
        public boolean verify(SkillDefinition definition){return accepted;}});}
    static SkillDefinition definition(String id,String version,java.util.List<SkillRef> dependencies){
        return SkillDefinition.builder().skillId(id).name(id).version(version).domain("general").description("test")
                .executionType(SkillExecutionType.JAVA_HANDLER).executionRef("handler").inputSchema("in")
                .outputSchema("out").permissionPolicy("default").dependencies(dependencies).timeoutMillis(500L)
                .tags(Collections.singleton("tag")).artifact(new SkillArtifact("local","author",1L,"abc", "sig",null)).build();
    }
    static SkillDefinition publish(InMemorySkillRegistry registry,SkillDefinition definition){
        registry.registerDraft(definition);registry.review(definition.getSkillId(),definition.getVersion());
        return registry.publish(definition.getSkillId(),definition.getVersion());
    }
    static void assertError(String code,Runnable action){try{action.run();fail("expected "+code);}
        catch(ClaraException exception){assertEquals(code,exception.getError().getCode());}}
}
