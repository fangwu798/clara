package com.clara.skill.runtime;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.skill.api.*;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class RoutingSkillHandlerTest {
    @Test public void routesByExactVersionedDefinitionReference() throws Exception {
        SkillHandler delegate = new SkillHandler(){
            public SkillExecutionResult execute(SkillDefinition definition, SkillExecutionRequest request){return result(definition,"execute");}
            public SkillExecutionResult resume(SkillDefinition definition, SkillResumeRequest request){return result(definition,"resume");}
        };
        RoutingSkillHandler router = new RoutingSkillHandler(Collections.singletonMap("handler", delegate));
        SkillDefinition definition = InMemorySkillRegistryTest.definition("one", "1", null);
        assertEquals("execute", router.execute(definition, null).getInstanceId());
    }

    @Test public void rejectsUnknownReferenceWithStableError() {
        RoutingSkillHandler router = new RoutingSkillHandler(Collections.singletonMap("other", new SkillHandler(){
            public SkillExecutionResult execute(SkillDefinition d,SkillExecutionRequest r){return null;}
            public SkillExecutionResult resume(SkillDefinition d,SkillResumeRequest r){return null;}
        }));
        try { router.execute(InMemorySkillRegistryTest.definition("one", "1", null), null); fail("expected missing handler"); }
        catch (ClaraException failure) { assertEquals(ErrorCodes.SKILL_HANDLER_NOT_FOUND, failure.getError().getCode()); }
        catch (Exception failure) { fail(failure.toString()); }
    }

    private static SkillExecutionResult result(SkillDefinition definition,String instance) {
        return new SkillExecutionResult(definition.getSkillId(),definition.getVersion(),instance,
                SkillExecutionStatus.SUCCEEDED,Collections.<String,Object>emptyMap(),1L);
    }
}
