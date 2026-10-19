package com.clara.skill.tool;

import com.clara.context.CancellationToken;
import com.clara.context.ConvContext;
import com.clara.enums.CapabilityKindEnum;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.skill.registry.SkillLoader;
import com.clara.skill.registry.SkillRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DefaultToolExecutor} 取消传播的单元测试：会话取消令牌随执行描述下发，已取消时脚本不启动即失败。
 */
public class DefaultToolExecutorCancellationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void cancelledConversationTokenAbortsToolBeforeLaunch() throws Exception {
        File root = tempFolder.newFolder("skill");
        File toolsDir = new File(root, "tools/python");
        assertTrue(toolsDir.mkdirs());
        String definition = "{\"id\":\"demo\",\"name\":\"demo\",\"type\":\"PYTHON\",\"description\":\"d\","
                + "\"script\":\"print('{}')\","
                + "\"parameters\":\"{\\\"type\\\":\\\"object\\\"}\"}";
        Files.write(new File(toolsDir, "demo.json").toPath(),
                definition.getBytes(StandardCharsets.UTF_8));

        SkillRegistry registry = new SkillRegistry(root, null, new SkillLoader(), null, null);
        registry.reload();
        DefaultToolExecutor executor = new DefaultToolExecutor(registry);

        CancellationToken token = new CancellationToken();
        token.cancel();
        ConvContext context = new ConvContext("u1", "c1", null, null, CapabilityKindEnum.CHAT, token);

        try (ConvContext.Scope ignored = ConvContext.open(context)) {
            executor.execute("demo", "{}");
            fail("已取消的会话令牌应让工具在执行前失败");
        } catch (ClaraException expected) {
            assertEquals(ErrorCategoryEnum.CANCELLED, expected.getError().getCategory());
        }
    }
}
