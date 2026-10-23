package com.clara.skill.registry;

import com.clara.core.skill.IToolDescriptionDecorator;
import com.clara.dto.skill.PromptDefinition;
import com.clara.dto.skill.ToolMethod;
import com.clara.dto.tool.ToolContext;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.exception.ClaraException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge cases for the JSON skill loader and the registry-backed tool catalog.
 */
public class SkillLoaderEdgeAndCatalogTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final SkillLoader loader = new SkillLoader();

    @Test
    public void parsesOptionalToolFields() throws Exception {
        File file = file("tool-min.json", "{\"id\":\"t1\",\"name\":\"n1\",\"type\":\"python\","
                + "\"description\":\"d1\",\"script\":\"s.py\","
                + "\"parameters\":{\"type\":\"object\"},\"metadata\":{\"k\":\"v\",\"nil\":null}}");
        ToolMethod method = loader.parseTool(file);
        assertEquals(ScriptTypeEnum.PYTHON, method.getType());
        assertEquals("{\"type\":\"object\"}", method.getParameters());
        assertEquals("v", method.getMetadata().get("k"));
        assertEquals("", method.getMetadata().get("nil"));

        File textParams = file("tool-text.json", "{\"id\":\"t2\",\"name\":\"n2\",\"type\":\"BUILTIN\","
                + "\"description\":\"d2\",\"script\":\"n2\",\"parameters\":\"{\\\"type\\\":\\\"object\\\"}\"}");
        assertEquals("{\"type\":\"object\"}", loader.parseTool(textParams).getParameters());

        File noParams = file("tool-none.json", "{\"id\":\"t3\",\"name\":\"n3\",\"type\":\"SHELL\","
                + "\"description\":\"d3\",\"script\":\"n3\"}");
        assertNull(loader.parseTool(noParams).getParameters());
    }

    @Test
    public void rejectsInvalidToolFiles() throws Exception {
        expectInvalid("{\"id\":\"t\",\"name\":\"n\",\"type\":\"GROOVY\",\"description\":\"d\",\"script\":\"s\"}");
        expectInvalid("{\"id\":\"t\",\"name\":\"n\",\"type\":\"PYTHON\",\"description\":\"d\"}");
        expectInvalid("{\"id\":\"\",\"name\":\"n\",\"type\":\"PYTHON\",\"description\":\"d\",\"script\":\"s\"}");
        expectInvalid("not-json");
    }

    @Test
    public void parsesPromptSegmentsAndRejectsEmptyOnes() throws Exception {
        PromptDefinition minimal = loader.parsePrompt(file("prompt-min.json",
                "{\"id\":\"p1\",\"description\":\"d1\",\"segments\":[{\"role\":\"system\",\"content\":\"c\"}]}"));
        assertEquals("p1", minimal.getId());
        assertEquals(1, minimal.getSegments().size());

        PromptDefinition full = loader.parsePrompt(file("prompt-full.json",
                "{\"id\":\"p3\",\"segments\":[{\"role\":\"system\",\"content\":\"c\"},{\"role\":\"user\",\"content\":\"u\"}]}"));
        assertEquals(2, full.getSegments().size());
        assertEquals("system", full.getSegments().get(0).getRole());
        assertEquals("user", full.getSegments().get(1).getRole());

        // A prompt without segments cannot be constructed, so the loader must report the file as invalid.
        expectInvalidPrompt("{\"id\":\"p2\",\"segments\":\"not-an-array\"}");
        // A segment without a role is invalid as well.
        expectInvalidPrompt("{\"id\":\"p4\",\"segments\":[{\"content\":\"c\"}]}");
    }

    @Test
    public void rejectsInvalidPromptFile() throws Exception {
        try {
            loader.parsePrompt(file("prompt-bad.json", "[1,2]"));
            fail("non-object prompt must fail");
        } catch (ClaraException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            loader.parsePrompt(new File(folder.getRoot(), "does-not-exist.json"));
            fail("missing file must fail");
        } catch (ClaraException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void catalogNormalizesNamesDescriptionsAndSchemas() throws Exception {
        File root = folder.newFolder("catalog-skill");
        File tools = new File(root, "tools");
        assertTrue(tools.mkdirs());
        write(new File(tools, "a.json"), toolJson("t1", "normal", "d1", "\"{\\\"type\\\":\\\"object\\\"}\""));
        write(new File(tools, "b.json"), toolJson("t2", "a.b c/\u4e2d", "d2", "\"not-json\""));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longName.append('x');
        }
        write(new File(tools, "c.json"), toolJson("t3", longName.toString(), "d3", "\"   \""));
        write(new File(tools, "d.json"), toolJson("t4", "four", "d4", "null"));

        SkillRegistry registry = new SkillRegistry(root, null, null, null, null);
        registry.reload();

        RegistryToolCatalog plain = new RegistryToolCatalog(registry);
        List<ToolContext> toolsOut = plain.availableTools();
        assertEquals(4, toolsOut.size());

        RegistryToolCatalog decorated = new RegistryToolCatalog(registry, new IToolDescriptionDecorator() {
            @Override
            public String decorate(String toolName, String description) {
                if ("t1".equals(toolName) || "normal".equals(toolName)) {
                    return "decorated";
                }
                if ("a.b c/\u4e2d".equals(toolName)) {
                    return "  ";
                }
                return null;
            }
        });
        boolean sawNormal = false;
        boolean sawLong = false;
        boolean sawIllegal = false;
        for (ToolContext context : decorated.availableTools()) {
            String name = context.getFunction().getName();
            if ("normal".equals(name)) {
                sawNormal = true;
                assertEquals("decorated", context.getFunction().getDescription());
                assertEquals("object", context.getFunction().getParameters().get("type"));
            }
            if (name.length() == 128) {
                sawLong = true;
                assertEquals(128, name.length());
                assertEquals(2, context.getFunction().getParameters().size());
            }
            if (name.startsWith("a_b_c_")) {
                sawIllegal = true;
                assertEquals("d2", context.getFunction().getDescription());
            }
        }
        assertTrue(sawNormal);
        assertTrue(sawLong);
        assertTrue(sawIllegal);

        try {
            new RegistryToolCatalog(null);
            fail("null registry must be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private void expectInvalidPrompt(String json) throws Exception {
        try {
            loader.parsePrompt(file("bad-prompt-" + System.nanoTime() + ".json", json));
            fail("invalid prompt must fail: " + json);
        } catch (ClaraException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private void expectInvalid(String json) throws Exception {
        try {
            loader.parseTool(file("bad-" + System.nanoTime() + ".json", json));
            fail("invalid tool file must fail: " + json);
        } catch (ClaraException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static String toolJson(String id, String name, String description, String parameters) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"type\":\"BUILTIN\","
                + "\"description\":\"" + description + "\",\"script\":\"" + id + "\","
                + "\"parameters\":" + parameters + "}";
    }

    private File file(String name, String content) throws IOException {
        File file = new File(folder.getRoot(), name);
        write(file, content);
        return file;
    }

    private static void write(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}