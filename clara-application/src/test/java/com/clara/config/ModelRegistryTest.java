package com.clara.config;

import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ProviderConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link ModelRegistry} 的单元测试：别名解析优先级、角色表映射、会话当前模型的切换与失败语义。
 *
 * <p>夹具用临时目录下的最小平台 YAML（结构对齐仓库既有 provider 配置），不访问网络、不需要凭据。
 */
public class ModelRegistryTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private Path alpha;
    private Path beta;
    private ModelRegistry registry;

    @Before
    public void setUp() throws Exception {
        alpha = write("alpha.yml", providerYaml("alpha-model"));
        beta = write("beta.yml", providerYaml("beta-model"));
        Map<String, String> roles = new LinkedHashMap<String, String>();
        roles.put("reviewer", "beta");
        registry = new ModelRegistry(Arrays.asList(alpha, beta), roles);
        registry.select(alpha);
    }

    @After
    public void tearDown() {
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    public void modelAliasOverridesRole() {
        assertEquals("alpha-model", modelId(registry.resolve("reviewer", "alpha")));
    }

    @Test
    public void roleResolvesThroughRoleTable() {
        assertEquals("beta-model", modelId(registry.resolve("reviewer", null)));
    }

    @Test
    public void roleFallsBackToAliasWhenNotMapped() {
        assertEquals("alpha-model", modelId(registry.resolve("alpha", null)));
    }

    @Test
    public void fallsBackToSelectedModelWhenUnspecified() {
        assertEquals("alpha-model", modelId(registry.resolve(null, null)));
    }

    @Test
    public void unknownAliasFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(null, "gamma"));
    }

    @Test
    public void missingSelectionFails() {
        ModelRegistry empty = new ModelRegistry(Collections.<Path>emptyList(), null);
        try {
            assertThrows(IllegalStateException.class, () -> empty.resolve(null, null));
            assertThrows(IllegalStateException.class, () -> empty.currentConfiguration());
        } finally {
            empty.close();
        }
    }

    @Test
    public void exposesAliasesAndCurrentModel() {
        assertTrue(registry.aliases().containsAll(Arrays.asList("alpha", "beta")));
        assertEquals("alpha", registry.currentAlias());
        assertEquals(alpha, registry.currentConfigPath());
    }

    @Test
    public void selectingAnotherConfigSwitchesCurrentModel() {
        registry.select(beta);

        assertEquals("beta", registry.currentAlias());
        assertEquals(beta, registry.currentConfigPath());
        assertEquals("beta-model", modelId(registry.currentConfiguration()));
        // 切换后未指定 role / model 的节点应退回新的当前模型
        assertEquals("beta-model", modelId(registry.resolve(null, null)));
    }

    @Test
    public void selectingUnknownConfigFailsFast() throws Exception {
        Path stranger = folder.newFile("gamma.yml").toPath();

        assertThrows(IllegalArgumentException.class, () -> registry.select(stranger));
    }

    private static String modelId(ProviderConfiguration configuration) {
        return configuration.toConfig(CapabilityKindEnum.CHAT).getModelId();
    }

    private Path write(String name, String content) throws Exception {
        Path file = folder.newFile(name).toPath();
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String providerYaml(String modelId) {
        return "provider: DEEPSEEK\n"
                + "credential:\n"
                + "  secretRef: deepseek\n"
                + "  environmentVariable: CLARA_DEEPSEEK_API_KEY\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - https://api.deepseek.com\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 180000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  CHAT:\n"
                + "    endpoint: https://api.deepseek.com/responses\n"
                + "    modelId: " + modelId + "\n"
                + "    apiProfile: RESPONSE\n"
                + "    parameters:\n"
                + "      maxTokens: 30000\n";
    }
}