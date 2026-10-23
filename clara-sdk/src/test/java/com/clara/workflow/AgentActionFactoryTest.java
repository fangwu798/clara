package com.clara.workflow;

import com.clara.config.ModelRegistry;
import com.clara.core.skill.IPromptProvider;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.usage.CapabilityUsageLedger;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link AgentActionFactory} 的单元测试：动作类型识别、prompt / promptText 二选一、
 * promptVars 与能力字段的校验。
 */
public class AgentActionFactoryTest {

    @Test
    public void supportsOnlyAgentKind() {
        AgentActionFactory factory = factory();

        assertTrue(factory.supports("AGENT"));
        assertTrue(factory.supports("agent"));
        assertFalse(factory.supports("REVIEW"));
        assertFalse(factory.supports(null));
    }

    @Test
    public void createsActionForPromptIdAndInlinePrompt() {
        AgentActionFactory factory = factory();

        IWorkflowAction byId = factory.create(spec("n1", "prompt", "  p1  "));
        IWorkflowAction inline = factory.create(spec("n2", "promptText", "正文"));

        assertNotNull(byId);
        assertNotNull(inline);
        assertTrue(byId instanceof AgentWorkflowAction);
        assertTrue(inline instanceof AgentWorkflowAction);
    }

    @Test
    public void convertsScalarAndMapAttributes() {
        AgentActionFactory factory = factory();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("prompt", 42);
        attributes.put("promptVars", Collections.singletonMap(7, "v"));
        attributes.put("capability", "IMAGE");
        attributes.put("attachments", Collections.singletonList("https://cdn/a.png"));

        assertNotNull(factory.create(new WorkflowActionSpec("n1", "AGENT", attributes)));
    }

    @Test
    public void rejectsInvalidSpecs() {
        AgentActionFactory factory = factory();

        assertIllegalArgument(() -> factory.create(null));
        assertIllegalArgument(() -> factory.create(new WorkflowActionSpec("n1", "AGENT",
                Collections.<String, Object>emptyMap())));
        assertIllegalArgument(() -> factory.create(spec("n1", "prompt", "   ")));
        Map<String, Object> badVars = new LinkedHashMap<>();
        badVars.put("prompt", "p");
        badVars.put("promptVars", "not-a-map");
        assertIllegalArgument(() -> factory.create(new WorkflowActionSpec("n1", "AGENT", badVars)));
    }

    private static AgentActionFactory factory() {
        IPromptProvider prompts = WorkflowActionTestSupport.prompts("P");
        ModelRegistry registry = new ModelRegistry(Collections.emptyList(), Collections.<String, String>emptyMap());
        return new AgentActionFactory(
                WorkflowActionTestSupport.orchestrator(WorkflowActionTestSupport.response("ok", 1, 1)).orchestrator(),
                prompts, registry);
    }

    private static WorkflowActionSpec spec(String nodeId, String key, String value) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(key, value);
        return new WorkflowActionSpec(nodeId, "AGENT", attributes);
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}