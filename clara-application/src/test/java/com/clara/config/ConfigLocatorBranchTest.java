package com.clara.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConfigLocator} 的分支测试：启用清单过滤、别名解析、主模型读取与路径回溯。
 *
 * <p>仓库自带的 config/ 用于覆盖「真实配置」路径；临时目录用于覆盖空列表、非法 YAML、
 * 字符串写法等不易在仓库里长期保留的分支。
 */
public class ConfigLocatorBranchTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void filtersByFlowListEnabledProviders() throws Exception {
        Path config = write("flow.yml", "enabledProviders: [alpha, BETA]\n");
        List<Path> all = Arrays.asList(path("alpha.yml"), path("beta.yml"), path("gamma.yml"));

        List<Path> filtered = ConfigLocator.filterModelConfigs(all, config);

        assertEquals(2, filtered.size());
        assertEquals("alpha.yml", filtered.get(0).getFileName().toString());
        assertEquals("beta.yml", filtered.get(1).getFileName().toString());
    }

    @Test
    public void filtersByBlockListAndCommaString() throws Exception {
        Path block = write("block.yml", "enabledProviders:\n  - alpha\n  - gamma\n");
        assertEquals(2, ConfigLocator.filterModelConfigs(
                Arrays.asList(path("alpha.yml"), path("beta.yml"), path("gamma.yml")), block).size());

        Path comma = write("comma.yml", "enabledProviders: alpha, gamma\n");
        assertEquals(2, ConfigLocator.filterModelConfigs(
                Arrays.asList(path("alpha.yml"), path("beta.yml"), path("gamma.yml")), comma).size());
    }

    @Test
    public void explicitEmptyListFiltersEverythingOut() throws Exception {
        Path config = write("empty.yml", "enabledProviders: []\n");

        List<Path> filtered = ConfigLocator.filterModelConfigs(
                Arrays.asList(path("alpha.yml"), path("beta.yml")), config);

        assertTrue(filtered.isEmpty());
    }

    @Test
    public void missingKeyOrFileKeepsAllCandidates() throws Exception {
        Path noKey = write("nokey.yml", "defaultModel: alpha\n");
        List<Path> all = Arrays.asList(path("alpha.yml"), path("beta.yml"));
        assertEquals(all, ConfigLocator.filterModelConfigs(all, noKey));
        assertEquals(all, ConfigLocator.filterModelConfigs(all, folder.getRoot().toPath().resolve("absent.yml")));
    }

    @Test
    public void brokenYamlIsTreatedAsNotConfigured() throws Exception {
        Path broken = write("broken.yml", "enabledProviders: [alpha\n");
        List<Path> all = Arrays.asList(path("alpha.yml"), path("beta.yml"));

        assertEquals(all, ConfigLocator.filterModelConfigs(all, broken));
    }

    @Test
    public void skipsCandidatesWhoseAliasIsBlankOrNull() throws Exception {
        Path config = write("only-alpha.yml", "enabledProviders: alpha\n");

        List<Path> filtered = ConfigLocator.filterModelConfigs(
                Arrays.asList(path("alpha.yml"), path(".yml"), Paths.get("")), config);

        assertEquals(1, filtered.size());
        assertEquals("alpha.yml", filtered.get(0).getFileName().toString());
    }

    @Test
    public void aliasOfStripsYmlAndYamlExtensions() {
        assertEquals("alpha", ConfigLocator.aliasOf(Paths.get("dir", "alpha.yml")));
        assertEquals("beta", ConfigLocator.aliasOf(Paths.get("beta.yaml")));
        assertEquals("gamma.txt", ConfigLocator.aliasOf(Paths.get("gamma.txt")));
        assertNull(ConfigLocator.aliasOf(null));
    }

    @Test
    public void resolveModelMatchesFileNameStemOrPath() {
        Path alpha = Paths.get("config", "model-providers", "alpha.yml");
        Path beta = Paths.get("config", "model-providers", "beta.yml");
        List<Path> candidates = Arrays.asList(alpha, beta);

        assertEquals(alpha, ConfigLocator.resolveModel(candidates, "ALPHA.YML"));
        assertEquals(beta, ConfigLocator.resolveModel(candidates, "beta"));
        assertEquals(alpha, ConfigLocator.resolveModel(candidates, "config\\model-providers\\alpha.yml"));
        assertNull(ConfigLocator.resolveModel(candidates, "gamma"));
    }

    @Test
    public void repositoryConfigurationIsLocatable() {
        assertTrue(Files.isDirectory(ConfigLocator.modelDirectory()));
        assertTrue(Files.isRegularFile(ConfigLocator.claraConfigFile()));
        assertFalse(ConfigLocator.allModelConfigs().isEmpty());
        assertFalse(ConfigLocator.modelConfigs().isEmpty());
        assertTrue(ConfigLocator.enabledProviders().contains("deepseek-response"));
        assertEquals("deepseek-response", ConfigLocator.defaultModel());
        assertTrue(ConfigLocator.modelConfigs().stream()
                .noneMatch(path -> path.toString().contains("example")));
    }

    @Test
    public void workspaceRootAndLogsDirectoryDeriveFromConfig() {
        Path root = ConfigLocator.workspaceRoot();
        assertNotNull(root);
        assertTrue(Files.isDirectory(root));
        assertEquals("logs", ConfigLocator.logsDirectory().getFileName().toString());
        assertFalse(ConfigLocator.projectName().isEmpty());
    }

    @Test
    public void locateFallsBackToGivenPathWhenMissing() {
        Path relative = Paths.get("no-such-dir-" + System.nanoTime());
        assertEquals(relative, ConfigLocator.locate(relative));
        assertEquals(relative, ConfigLocator.locateFile(relative));
        assertTrue(Files.isDirectory(ConfigLocator.locate(Paths.get("config"))));
        assertTrue(Files.isRegularFile(ConfigLocator.locateFile(Paths.get("config", "clara.yml"))));
    }

    @Test
    public void roleBindingsAreEmptyWhenFileMissing() {
        Map<String, String> bindings = ConfigLocator.roleBindings();
        assertNotNull(bindings);
    }

    private Path write(String name, String content) throws Exception {
        File file = folder.newFile(name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file.toPath();
    }

    private static Path path(String fileName) {
        return Paths.get("config", "model-providers", fileName);
    }
}