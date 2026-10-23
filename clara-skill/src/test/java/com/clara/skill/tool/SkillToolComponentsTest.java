package com.clara.skill.tool;

import com.clara.api.IBuiltinTool;
import com.clara.api.IScriptExecutor;
import com.clara.core.tool.IToolInvocation;
import com.clara.dto.tool.ToolResult;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.exception.ClaraException;
import com.clara.skill.tool.builtin.BuiltinToolExecutor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the small tool-layer components that have no external dependencies:
 * BuiltinToolExecutor, MockTool, DefaultToolInvoker, ScriptExecutionSpec and ScriptExecutorRegistry.
 */
public class SkillToolComponentsTest {

    @Test
    public void builtinExecutorDispatchesByScriptName() {
        BuiltinToolExecutor executor = new BuiltinToolExecutor(Arrays.asList(
                builtin("echo", "first"), builtin("sum", "42")));
        assertEquals(ScriptTypeEnum.BUILTIN, executor.supportedType());
        assertEquals("first", executor.execute(new ScriptExecutionSpec(
                ScriptTypeEnum.BUILTIN, "echo", "{\"a\":1}", 1000L)));
        assertEquals("42", executor.execute(new ScriptExecutionSpec(
                ScriptTypeEnum.BUILTIN, "sum", null, 1000L)));
    }

    @Test
    public void builtinExecutorRejectsInvalidRegistration() {
        try {
            new BuiltinToolExecutor(null);
            fail("null list must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("tools"));
        }
        BuiltinToolExecutor executor = new BuiltinToolExecutor(Collections.<IBuiltinTool>emptyList());
        try {
            executor.register(null);
            fail("null tool must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            executor.register(builtin("  ", "x"));
            fail("blank name must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            executor.execute(null);
            fail("null spec must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void builtinExecutorReportsUnknownToolAndAllowsOverride() {
        BuiltinToolExecutor executor = new BuiltinToolExecutor(Collections.<IBuiltinTool>emptyList());
        try {
            executor.execute(new ScriptExecutionSpec(ScriptTypeEnum.BUILTIN, "missing", "{}", 1000L));
            fail("unknown builtin must fail");
        } catch (ClaraException expected) {
            assertTrue(expected.getMessage().contains("missing"));
        }
        executor.register(builtin("same", "v1"));
        executor.register(builtin("same", "v2"));
        assertEquals("v2", executor.execute(new ScriptExecutionSpec(
                ScriptTypeEnum.BUILTIN, "same", "{}", 1000L)));
    }

    @Test
    public void mockToolExposesDeclarationAndEchoesArguments() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        MockTool tool = new MockTool("lookup", "demo tool", schema);
        assertEquals("lookup", tool.name());
        assertEquals("demo tool", tool.description());
        assertEquals(schema, tool.parameters());
        assertEquals("{mock=true, args={\"q\":\"x\"}}", tool.invoke("{\"q\":\"x\"}"));
        assertEquals("{mock=true, args=null}", tool.invoke(null));

        try {
            new MockTool(" ", "d", schema);
            fail("blank name must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new MockTool("n", "d", null);
            fail("null parameters must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void defaultToolInvokerWrapsExecutorOutput() {
        final List<String> calls = new ArrayList<>();
        DefaultToolInvoker invoker = new DefaultToolInvoker(new com.clara.core.skill.IToolExecutor() {
            @Override
            public String execute(String toolName, String argumentsJson) {
                calls.add(toolName + "|" + argumentsJson);
                return "{\"ok\":true}";
            }
        });
        ToolResult result = invoker.invoke(invocation("call-1", "echo", "{\"a\":1}"));
        assertEquals("call-1", result.getCallId());
        assertEquals("{\"ok\":true}", result.getOutput());
        assertEquals(Collections.singletonList("echo|{\"a\":1}"), calls);

        try {
            new DefaultToolInvoker(null);
            fail("null executor must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            invoker.invoke(null);
            fail("null invocation must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void scriptExecutionSpecValidatesRequiredFields() {
        ScriptExecutionSpec spec = new ScriptExecutionSpec(ScriptTypeEnum.PYTHON, "a.py", null, 10L);
        assertEquals(ScriptTypeEnum.PYTHON, spec.getType());
        assertEquals("a.py", spec.getScript());
        assertEquals(10L, spec.getTimeoutMillis());
        assertEquals(null, spec.getCancellationToken());

        expectInvalid(null, "a.py", 10L);
        expectInvalid(ScriptTypeEnum.PYTHON, "  ", 10L);
        expectInvalid(ScriptTypeEnum.PYTHON, "a.py", 0L);
    }

    @Test
    public void scriptExecutorRegistryResolvesDefaultsAndCustomExecutors() {
        ScriptExecutorRegistry registry = new ScriptExecutorRegistry();
        assertEquals(ScriptTypeEnum.PYTHON, registry.resolve(ScriptTypeEnum.PYTHON).supportedType());
        assertEquals(ScriptTypeEnum.JAVASCRIPT, registry.resolve(ScriptTypeEnum.JAVASCRIPT).supportedType());
        assertEquals(ScriptTypeEnum.SHELL, registry.resolve(ScriptTypeEnum.SHELL).supportedType());

        final IScriptExecutor custom = new FakeExecutor(ScriptTypeEnum.BUILTIN);
        registry.register(custom);
        assertEquals(custom, registry.resolve(ScriptTypeEnum.BUILTIN));

        try {
            registry.register(null);
            fail("null executor must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            registry.resolve(null);
            fail("null type must fail");
        } catch (ClaraException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static void expectInvalid(ScriptTypeEnum type, String script, long timeout) {
        try {
            new ScriptExecutionSpec(type, script, "{}", timeout);
            fail("invalid spec must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static IBuiltinTool builtin(final String name, final String output) {
        return new IBuiltinTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String execute(String argumentsJson) {
                return output;
            }
        };
    }

    private static IToolInvocation invocation(final String callId, final String name, final String arguments) {
        return new IToolInvocation() {
            @Override
            public String callId() {
                return callId;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String arguments() {
                return arguments;
            }
        };
    }

    private static final class FakeExecutor implements IScriptExecutor {
        private final ScriptTypeEnum type;

        private FakeExecutor(ScriptTypeEnum type) {
            this.type = type;
        }

        @Override
        public ScriptTypeEnum supportedType() {
            return type;
        }

        @Override
        public String execute(ScriptExecutionSpec spec) {
            return "{}";
        }
    }
}