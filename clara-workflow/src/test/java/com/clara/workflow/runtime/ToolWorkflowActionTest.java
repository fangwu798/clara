package com.clara.workflow.runtime;

import com.clara.core.skill.IToolInvoker;
import com.clara.core.tool.IToolInvocation;
import com.clara.dto.tool.ToolResult;
import com.clara.dto.workflow.ExecutionScope;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TOOL workflow action: argument template resolution and tool invocation.
 */
public class ToolWorkflowActionTest {

    @Test
    public void invokesToolWithResolvedArguments() {
        RecordingInvoker invoker = new RecordingInvoker();
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("city", "${city}");
        template.put("nested", Collections.singletonMap("value", "${count}"));
        ToolWorkflowAction action = new ToolWorkflowAction(invoker, "node-1", "weather", template);
        ExecutionScope scope = new ExecutionScope(Collections.<String, Object>singletonMap("city", "Shanghai"));
        scope.addInputs(Collections.<String, Object>singletonMap("count", 3));

        Map<String, Object> output = action.execute(scope);

        assertEquals("{\"city\":\"Shanghai\",\"nested\":{\"value\":3}}", invoker.arguments);
        assertEquals("node-1", invoker.callId);
        assertEquals("weather", invoker.toolName);
        assertEquals("done", output.get("output"));
    }

    @Test
    public void usesEmptyArgumentsWhenTemplateIsNull() {
        RecordingInvoker invoker = new RecordingInvoker();
        ToolWorkflowAction action = new ToolWorkflowAction(invoker, "n", "t", null);
        action.execute(new ExecutionScope(new HashMap<String, Object>()));
        assertEquals("{}", invoker.arguments);
    }

    @Test
    public void validatesConstructionAndScope() {
        assertIllegalArgument(() -> new ToolWorkflowAction(null, "n", "t", null));
        assertIllegalArgument(() -> new ToolWorkflowAction(new RecordingInvoker(), " ", "t", null));
        assertIllegalArgument(() -> new ToolWorkflowAction(new RecordingInvoker(), "n", " ", null));
        ToolWorkflowAction action = new ToolWorkflowAction(new RecordingInvoker(), "n", "t", null);
        assertIllegalArgument(() -> action.execute(null));
    }

    @Test
    public void failsWhenArgumentCannotBeResolved() {
        ToolWorkflowAction action = new ToolWorkflowAction(new RecordingInvoker(), "n", "t",
                Collections.<String, Object>singletonMap("x", "${missing}"));
        try {
            action.execute(new ExecutionScope(new HashMap<String, Object>()));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("missing"));
        }
    }

    private static final class RecordingInvoker implements IToolInvoker {
        private String callId;
        private String toolName;
        private String arguments;

        @Override
        public ToolResult invoke(IToolInvocation invocation) {
            callId = invocation.callId();
            toolName = invocation.name();
            arguments = invocation.arguments();
            return new ToolResult(callId, "done");
        }
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
