package com.clara.workflow.runtime;

import com.clara.core.skill.IToolInvoker;
import com.clara.core.tool.IToolInvocation;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.dto.tool.ToolResult;
import com.clara.dto.workflow.ExecutionScope;
import com.clara.dto.workflow.WorkflowActionSpec;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Factory that turns a TOOL node spec into an executable action.
 */
public class ToolActionFactoryTest {

    private final IToolInvoker invoker = new IToolInvoker() {
        @Override
        public ToolResult invoke(IToolInvocation invocation) {
            return new ToolResult(invocation.callId(), invocation.arguments());
        }
    };

    @Test
    public void supportsOnlyToolKind() {
        ToolActionFactory factory = new ToolActionFactory(invoker);
        assertTrue(factory.supports("TOOL"));
        assertTrue(factory.supports("tool"));
        assertFalse(factory.supports("AGENT"));
        assertFalse(factory.supports(null));
    }

    @Test
    public void createsActionWithArguments() {
        ToolActionFactory factory = new ToolActionFactory(invoker);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tool", "echo");
        attributes.put("arguments", Collections.singletonMap("text", "hi"));

        IWorkflowAction action = factory.create(new WorkflowActionSpec("n1", "TOOL", attributes));
        Map<String, Object> output = action.execute(new ExecutionScope(new HashMap<String, Object>()));

        assertEquals("{\"text\":\"hi\"}", output.get("output"));
    }

    @Test
    public void coercesNonStringToolValueAndToleratesMissingArguments() {
        ToolActionFactory factory = new ToolActionFactory(invoker);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tool", 42);
        IWorkflowAction action = factory.create(new WorkflowActionSpec("n1", "TOOL", attributes));
        assertEquals("{}", action.execute(new ExecutionScope(new HashMap<String, Object>())).get("output"));
    }

    @Test
    public void rejectsInvalidSpecs() {
        ToolActionFactory factory = new ToolActionFactory(invoker);
        assertIllegalArgument(() -> new ToolActionFactory(null));
        assertIllegalArgument(() -> factory.create(null));
        assertIllegalArgument(() -> factory.create(new WorkflowActionSpec("n1", "TOOL",
                Collections.<String, Object>emptyMap())));
        assertIllegalArgument(() -> factory.create(new WorkflowActionSpec("n1", "TOOL",
                Collections.<String, Object>singletonMap("tool", " "))));
        assertIllegalArgument(() -> factory.create(specWithArguments("not-a-map")));
    }

    private static WorkflowActionSpec specWithArguments(Object arguments) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tool", "x");
        attributes.put("arguments", arguments);
        return new WorkflowActionSpec("n1", "TOOL", attributes);
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
