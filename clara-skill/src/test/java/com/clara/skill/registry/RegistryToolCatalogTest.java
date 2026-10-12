package com.clara.skill.registry;

import com.clara.dto.tool.ToolContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link RegistryToolCatalog} 单元测试：把注册表工具转换为工具声明、规整函数名、解析入参 Schema。
 */
public class RegistryToolCatalogTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void convertsRegisteredToolsToDeclarations() throws Exception {
        File root = tempFolder.newFolder("skill");
        File toolsDir = new File(root, "tools/python");
        assertTrue(toolsDir.mkdirs());
        write(new File(toolsDir, "my_tool.json"),
                "{\"id\":\"my_tool\",\"name\":\"my tool.name!\",\"type\":\"PYTHON\","
                        + "\"description\":\"desc\",\"script\":\"x.py\","
                        + "\"parameters\":\"{\\\"type\\\":\\\"object\\\",\\\"properties\\\":"
                        + "{\\\"a\\\":{\\\"type\\\":\\\"string\\\"}}}\"}");

        SkillRegistry registry = new SkillRegistry(root, null, new SkillLoader(), null, null);
        registry.reload();

        List<ToolContext> declarations = new RegistryToolCatalog(registry).availableTools();

        assertEquals(1, declarations.size());
        assertEquals("my_tool_name_", declarations.get(0).getFunction().getName());
        assertEquals("desc", declarations.get(0).getFunction().getDescription());
        Object properties = declarations.get(0).getFunction().getParameters().get("properties");
        assertNotNull(properties);
        assertTrue(((Map<?, ?>) properties).containsKey("a"));
    }

    @Test
    public void returnsEmptyWhenRegistryHasNoTools() throws Exception {
        SkillRegistry registry = new SkillRegistry(tempFolder.newFolder("empty"), null,
                new SkillLoader(), null, null);
        registry.reload();

        assertTrue(new RegistryToolCatalog(registry).availableTools().isEmpty());
    }

    private static void write(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
