package com.clara.skill.registry;

import com.clara.dto.skill.PromptDefinition;
import com.clara.dto.skill.PromptSegment;
import com.clara.dto.skill.ToolMethod;
import com.clara.enums.skill.ScriptTypeEnum;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link SkillLoader} 的单元测试：解析内置工具方法与提示词资源，以及内联提示词分段。
 */
public class SkillLoaderTest {

    private final SkillLoader loader = new SkillLoader();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File resourceFile(String path) throws Exception {
        URL url = SkillLoaderTest.class.getClassLoader().getResource(path);
        assertNotNull("resource not found: " + path, url);
        return new File(url.toURI());
    }

    @Test
    public void parsesBuiltinEchoTool() throws Exception {
        File file = resourceFile("config/tools/python/echo.json");
        ToolMethod method = loader.parseTool(file);
        assertEquals("echo", method.getId());
        assertEquals("echo", method.getName());
        assertEquals(ScriptTypeEnum.PYTHON, method.getType());
        assertNotNull(method.getDescription());
        assertFalse(method.getDescription().trim().isEmpty());
        assertNotNull(method.getParameters());
        assertFalse(method.getScript().trim().isEmpty());
        assertEquals("example", method.getMetadata().get("domain"));
        assertEquals("echo,python", method.getMetadata().get("tags"));
    }

    @Test
    public void parsesBuiltinSummarizePrompt() throws Exception {
        File file = resourceFile("config/prompt/summarize.json");
        PromptDefinition definition = loader.parsePrompt(file);
        assertEquals("summarize", definition.getId());
        assertNotNull(definition.getDescription());
        assertFalse(definition.getSegments().isEmpty());
        boolean hasSystem = false;
        for (PromptSegment segment : definition.getSegments()) {
            if ("system".equals(segment.getRole())) {
                hasSystem = true;
            }
        }
        assertTrue(hasSystem);
    }

    @Test
    public void parsesInlinePromptSegments() throws Exception {
        String json = "{ \"id\": \"p1\", \"description\": \"d\", \"segments\": ["
                + "{ \"role\": \"system\", \"content\": \"第一条\" },"
                + "{ \"role\": \"user\", \"content\": \"第二条\" } ] }";
        File file = tempFolder.newFile("p1.json");
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        PromptDefinition definition = loader.parsePrompt(file);
        assertEquals("p1", definition.getId());
        assertEquals(2, definition.getSegments().size());
        assertEquals("system", definition.getSegments().get(0).getRole());
        assertEquals("user", definition.getSegments().get(1).getRole());
    }
}
