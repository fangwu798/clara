package com.clara.skill.tool;

import com.clara.api.IScriptExecutor;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.exception.ClaraException;
import com.clara.skill.executor.SkillPermissionAuthorizer;
import com.clara.skill.executor.SkillSchemaValidator;
import com.clara.skill.registry.SkillRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DefaultToolExecutor dispatch chain: registry lookup, permission, schema validation and executor routing.
 */
public class DefaultToolExecutorTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void dispatchesThroughPermissionSchemaAndExecutor() throws Exception {
        SkillRegistry registry = registry("echo", "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");
        RecordingExecutor executor = new RecordingExecutor("{\"ok\":true}");
        ScriptExecutorRegistry executors = new ScriptExecutorRegistry();
        executors.register(executor);
        DefaultToolExecutor toolExecutor = new DefaultToolExecutor(registry, executors,
                new SkillSchemaValidator(), new SkillPermissionAuthorizer(), 5000L);

        String output = toolExecutor.execute("echo", "{\"q\":\"hi\"}");
        assertEquals("{\"ok\":true}", output);
        assertEquals("{\"q\":\"hi\"}", executor.lastSpec.getArgumentsJson());
        assertEquals(5000L, executor.lastSpec.getTimeoutMillis());
        assertEquals(ScriptTypeEnum.BUILTIN, executor.lastSpec.getType());
    }

    @Test
    public void rejectsUnknownToolAndDeniedPermission() throws Exception {
        SkillRegistry registry = registry("echo", null);
        DefaultToolExecutor toolExecutor = new DefaultToolExecutor(registry, new ScriptExecutorRegistry(),
                new SkillSchemaValidator(), new SkillPermissionAuthorizer(Collections.singletonList("other")), 1000L);
        try {
            toolExecutor.execute("missing", "{}");
            fail("unknown tool must fail");
        } catch (ClaraException expected) {
            assertTrue(expected.getMessage().contains("missing"));
        }
        try {
            toolExecutor.execute("echo", "{}");
            fail("denied tool must fail");
        } catch (ClaraException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void rejectsInvalidInputAndOutput() throws Exception {
        SkillRegistry registry = registry("echo", "{\"type\":\"object\",\"required\":[\"q\"]}");
        RecordingExecutor executor = new RecordingExecutor("not-json");
        ScriptExecutorRegistry executors = new ScriptExecutorRegistry();
        executors.register(executor);
        DefaultToolExecutor toolExecutor = new DefaultToolExecutor(registry, executors,
                new SkillSchemaValidator(), new SkillPermissionAuthorizer(), 1000L);

        try {
            toolExecutor.execute("echo", "{}");
            fail("schema violation must fail");
        } catch (ClaraException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            toolExecutor.execute("echo", "{\"q\":1}");
            fail("non-JSON output must fail");
        } catch (ClaraException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            toolExecutor.execute("echo", "{bad");
            fail("invalid input JSON must fail");
        } catch (ClaraException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void validatesConstructorArguments() throws Exception {
        SkillRegistry registry = registry("echo", null);
        try {
            new DefaultToolExecutor(null);
            fail("null registry must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new DefaultToolExecutor(registry, null, new SkillSchemaValidator(),
                    new SkillPermissionAuthorizer(), 10L);
            fail("null executors must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new DefaultToolExecutor(registry, new ScriptExecutorRegistry(), null,
                    new SkillPermissionAuthorizer(), 10L);
            fail("null validator must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new DefaultToolExecutor(registry, new ScriptExecutorRegistry(), new SkillSchemaValidator(),
                    null, 10L);
            fail("null authorizer must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new DefaultToolExecutor(registry, new ScriptExecutorRegistry(), new SkillSchemaValidator(),
                    new SkillPermissionAuthorizer(), 0L);
            fail("non-positive timeout must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private SkillRegistry registry(String name, String parameters) throws Exception {
        File root = folder.newFolder("executor-skill");
        File tools = new File(root, "tools");
        if (!tools.isDirectory() && !tools.mkdirs()) {
            throw new IOException("cannot create " + tools);
        }
        StringBuilder json = new StringBuilder();
        json.append("{\"id\":\"").append(name).append("\",\"name\":\"").append(name)
                .append("\",\"type\":\"BUILTIN\",\"description\":\"demo\",\"script\":\"").append(name).append('"');
        if (parameters != null) {
            json.append(",\"parameters\":").append(parameters);
        }
        json.append('}');
        Files.write(new File(tools, name + ".json").toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        SkillRegistry registry = new SkillRegistry(root, null, null, null, null);
        registry.reload();
        return registry;
    }

    private static final class RecordingExecutor implements IScriptExecutor {
        private final String output;
        private ScriptExecutionSpec lastSpec;

        private RecordingExecutor(String output) {
            this.output = output;
        }

        @Override
        public ScriptTypeEnum supportedType() {
            return ScriptTypeEnum.BUILTIN;
        }

        @Override
        public String execute(ScriptExecutionSpec spec) {
            this.lastSpec = spec;
            return output;
        }
    }
}