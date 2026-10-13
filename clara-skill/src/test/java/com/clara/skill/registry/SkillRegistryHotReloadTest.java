package com.clara.skill.registry;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link SkillRegistry} 运行期改动检测的单元测试：{@code prompt/} 与 {@code scripts/} 下的文件被
 * 新增 / 修改 / 删除后，在**下一次读取**时自动生效，无需重启进程或显式调用 {@code reload()}。
 */
public class SkillRegistryHotReloadTest {

    private static final String PROMPT_TEMPLATE =
            "{\"id\":\"%s\",\"description\":\"%s\",\"segments\":[{\"role\":\"system\",\"content\":\"%s\"}]}";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void picksUpChangedPromptOnNextLookup() throws Exception {
        File root = folder.newFolder("skill-prompt");
        File prompt = writePrompt(root, "hot_prompt", "v1");

        SkillRegistry registry = new SkillRegistry(null, root, null, null, null);
        registry.reload();
        assertEquals("v1", registry.requirePrompt("hot_prompt").getDescription());

        write(prompt, String.format(PROMPT_TEMPLATE, "hot_prompt", "v2-longer", "v2-longer"));

        assertEquals("v2-longer", registry.findPrompt("hot_prompt").getDescription());
    }

    @Test
    public void dropsRemovedPromptOnNextLookup() throws Exception {
        File root = folder.newFolder("skill-removed");
        File prompt = writePrompt(root, "gone_prompt", "v1");

        SkillRegistry registry = new SkillRegistry(null, root, null, null, null);
        registry.reload();
        assertNotNull(registry.findPrompt("gone_prompt"));

        assertTrue(prompt.delete());

        assertNull(registry.findPrompt("gone_prompt"));
    }

    @Test
    public void picksUpChangedScriptDescriptionOnNextLookup() throws Exception {
        Assume.assumeTrue("未安装 Python，跳过脚本热加载测试", pythonAvailable());

        File root = folder.newFolder("skill-scripts");
        File script = writeScript(root, "hot_tool", "v1");

        SkillRegistry registry = new SkillRegistry(null, root, null, null, null);
        registry.reload();
        assertEquals("v1", registry.requireToolByName("hot_tool").getDescription());

        write(script, scriptSource("hot_tool", "v2-longer-description"));

        assertEquals("v2-longer-description",
                registry.findToolByName("hot_tool").getDescription());
    }

    @Test
    public void discoversScriptsInPurposeSubdirectories() throws Exception {
        Assume.assumeTrue("未安装 Python，跳过脚本目录扫描测试", pythonAvailable());

        File root = folder.newFolder("skill-grouped");
        File scripts = newFolder(root, "scripts");
        write(new File(newFolder(scripts, "file-ops"), "nested_tool.py"),
                scriptSource("nested_tool", "nested"));
        // 下划线开头的目录（如共享辅助模块目录、__pycache__）不参与工具发现
        write(new File(newFolder(scripts, "_shared"), "helper_tool.py"),
                scriptSource("helper_tool", "helper"));

        SkillRegistry registry = new SkillRegistry(null, root, null, null, null);
        registry.reload();

        assertNotNull("按用途子目录存放的脚本应被发现", registry.findToolByName("nested_tool"));
        assertNull("下划线开头目录中的脚本不应被发现", registry.findToolByName("helper_tool"));
    }

    private static File writePrompt(File root, String id, String description) throws IOException {
        File file = new File(newFolder(root, "prompt"), id + ".json");
        write(file, String.format(PROMPT_TEMPLATE, id, description, description));
        return file;
    }

    private static File writeScript(File root, String id, String description) throws IOException {
        File file = new File(newFolder(root, "scripts"), id + ".py");
        write(file, scriptSource(id, description));
        return file;
    }

    private static File newFolder(File root, String name) throws IOException {
        File dir = new File(root, name);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建测试目录：" + dir);
        }
        return dir;
    }

    /**
     * 生成自描述脚本源码：仅 {@code --describe} 时输出元数据，描述文本随入参变化。
     */
    private static String scriptSource(String id, String description) {
        return "import json, sys\n"
                + "if '--describe' in sys.argv[1:]:\n"
                + "    print(json.dumps({'id': '" + id + "', 'name': '" + id + "', 'type': 'PYTHON',\n"
                + "        'description': '" + description + "',\n"
                + "        'parameters': {'type': 'object', 'properties': {}},\n"
                + "        'metadata': {'domain': 'test'}}))\n"
                + "    sys.exit(0)\n"
                + "print('{}')\n";
    }

    private static void write(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean pythonAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder(pythonPath(), "--version").start();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String pythonPath() {
        String configured = System.getenv("PYTHON_PATH");
        return configured == null || configured.trim().isEmpty() ? "python" : configured;
    }
}
