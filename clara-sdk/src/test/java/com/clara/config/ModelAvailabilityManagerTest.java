package com.clara.config;

import com.clara.enums.AvailabilityLevelEnum;
import com.clara.model.availability.ModelCheckResult;
import com.clara.model.availability.AvailabilityLine;
import com.clara.model.availability.api.IModelProbe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelAvailabilityManagerTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void keepsOnlyUsableModelsAndSelectsFastest() throws Exception {
        Path clara = temporary.newFile("clara.yml").toPath();
        Files.write(clara, Arrays.asList(
                "# keep this comment",
                "defaultModel: old",
                "enabledProviders:",
                "  - old",
                "milvus:",
                "  enabled: true"), StandardCharsets.UTF_8);
        List<Path> configs = Arrays.asList(config("deepseek-completion.yml"),
                config("deepseek-response.yml"), config("kimi-completion.yml"));
        IModelProbe probe = (path, alias, provider, chat, timeout) -> {
            if (alias.equals("deepseek-completion")) {
                return ModelCheckResult.available(path, alias,
                        provider.getProvider().name(), chat.getModelId(), 120L);
            }
            if (alias.equals("deepseek-response")) {
                return ModelCheckResult.available(path, alias,
                        provider.getProvider().name(), chat.getModelId(), 35L);
            }
            return ModelCheckResult.unavailable(path, alias,
                    provider.getProvider().name(), chat.getModelId(), "denied");
        };
        List<AvailabilityLine> output = new ArrayList<>();

        List<Path> usable = new ModelAvailabilityManager(name -> "test-key", probe, 1000L)
                .checkAndPersist(configs, clara, output::add);

        assertEquals(Arrays.asList(configs.get(1), configs.get(0)), usable);
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(Files.readAllBytes(clara));
        assertEquals("deepseek-response", root.get("defaultModel").asText());
        assertEquals(2, root.get("enabledProviders").size());
        assertEquals("deepseek-response", root.get("enabledProviders").get(0).asText());
        assertTrue(root.get("milvus").get("enabled").asBoolean());
        assertTrue(Files.readAllLines(clara, StandardCharsets.UTF_8).contains("# keep this comment"));
        assertTrue(texts(output).stream().anyMatch(line -> line.contains("首字符响应=35ms")));
        assertEquals(AvailabilityLevelEnum.OK, levelOf(output, "deepseek-response"));
        assertEquals(AvailabilityLevelEnum.FAIL, levelOf(output, "kimi-completion"));
    }

    @Test
    public void keepsConfiguredDefaultWhenAvailable() throws Exception {
        Path clara = temporary.newFile("keep-default.yml").toPath();
        Files.write(clara, Arrays.asList(
                "defaultModel: deepseek-completion",
                "enabledProviders: [deepseek-completion, deepseek-response]"),
                StandardCharsets.UTF_8);
        List<Path> configs = Arrays.asList(config("deepseek-completion.yml"),
                config("deepseek-response.yml"));
        IModelProbe probe = (path, alias, provider, chat, timeout) -> ModelCheckResult.available(
                path, alias, provider.getProvider().name(), chat.getModelId(),
                alias.equals("deepseek-response") ? 35L : 120L);
        List<AvailabilityLine> output = new ArrayList<>();

        List<Path> usable = new ModelAvailabilityManager(name -> "test-key", probe, 1000L)
                .checkAndPersist(configs, clara, output::add);

        assertEquals(Arrays.asList(configs.get(1), configs.get(0)), usable);
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(Files.readAllBytes(clara));
        assertEquals("deepseek-completion", root.get("defaultModel").asText());
        assertTrue(texts(output).stream().anyMatch(line ->
                line.contains("默认模型：deepseek-completion") && line.contains("120ms")));
    }
    @Test
    public void skipsMissingCredentialWithoutProbingAndWritesEmptyList() throws Exception {
        Path clara = temporary.newFile("empty.yml").toPath();
        Files.write(clara, Collections.singletonList("milvus:\n  enabled: false"), StandardCharsets.UTF_8);
        AtomicInteger probes = new AtomicInteger();
        IModelProbe probe = (path, alias, provider, chat, timeout) -> {
            probes.incrementAndGet();
            return ModelCheckResult.available(path, alias, "x", "x", 1L);
        };

        List<Path> usable = new ModelAvailabilityManager(name -> null, probe, 1000L)
                .checkAndPersist(Collections.singletonList(config("deepseek-completion.yml")), clara, null);

        assertTrue(usable.isEmpty());
        assertEquals(0, probes.get());
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(Files.readAllBytes(clara));
        assertTrue(root.has("defaultModel"));
        assertTrue(root.get("defaultModel").isNull());
        assertTrue(root.get("enabledProviders").isArray());
        assertEquals(0, root.get("enabledProviders").size());
    }

    @Test
    public void skipsConfigurationWithoutChatCapability() throws Exception {
        Path provider = temporary.newFile("image-only.yml").toPath();
        Files.write(provider, Arrays.asList(
                "provider: VOLCENGINE",
                "credential:",
                "  secretRef: volcengine",
                "  environmentVariable: CLARA_VOLCENGINE_API_KEY",
                "transport:",
                "  allowedOrigins:",
                "    - https://ark.cn-beijing.volces.com",
                "  connectTimeoutMillis: 10000",
                "  readTimeoutMillis: 30000",
                "  maxRequestBytes: 8388608",
                "  maxResponseBytes: 33554432",
                "  allowHttp: false",
                "capabilities:",
                "  IMAGE:",
                "    endpoint: https://ark.cn-beijing.volces.com/api/v3/images/generations",
                "    modelId: image-model",
                "    apiProfile: ark-v3-images"), StandardCharsets.UTF_8);
        Path clara = temporary.newFile("image-only-clara.yml").toPath();
        AtomicInteger environmentReads = new AtomicInteger();
        AtomicInteger probes = new AtomicInteger();
        List<AvailabilityLine> output = new ArrayList<>();

        List<Path> usable = new ModelAvailabilityManager(name -> {
            environmentReads.incrementAndGet();
            return "test-key";
        }, (path, alias, configuration, chat, timeout) -> {
            probes.incrementAndGet();
            return ModelCheckResult.available(path, alias, "x", "x", 1L);
        }, 1000L).checkAndPersist(Collections.singletonList(provider), clara, output::add);

        assertTrue(usable.isEmpty());
        assertEquals(0, environmentReads.get());
        assertEquals(0, probes.get());
        assertTrue(texts(output).stream().anyMatch(line -> line.contains("[跳过]") && line.contains("未配置 CHAT 能力")));
        assertEquals(AvailabilityLevelEnum.WARN, levelOf(output, "image-only"));
    }

    @Test
    public void explicitEmptyEnabledProvidersLoadsNoModels() throws Exception {
        Path clara = temporary.newFile("filter.yml").toPath();
        Files.write(clara, Collections.singletonList("enabledProviders: []"), StandardCharsets.UTF_8);
        List<Path> all = Arrays.asList(Paths.get("alpha.yml"), Paths.get("beta.yml"));

        assertTrue(ConfigLocator.filterModelConfigs(all, clara).isEmpty());

        Files.write(clara, Collections.singletonList("defaultModel: alpha"), StandardCharsets.UTF_8);
        assertEquals(all, ConfigLocator.filterModelConfigs(all, clara));
    }

    @Test
    public void invalidProviderDoesNotAbortOtherChecks() throws Exception {
        Path invalid = temporary.newFile("invalid.yml").toPath();
        Files.write(invalid, Collections.singletonList("provider: INVALID"), StandardCharsets.UTF_8);
        Path clara = temporary.newFile("invalid-clara.yml").toPath();
        List<AvailabilityLine> output = new ArrayList<>();

        List<Path> usable = new ModelAvailabilityManager(name -> "key",
                (path, alias, provider, chat, timeout) -> {
                    throw new AssertionError("invalid configuration must not be probed");
                }, 1000L).checkAndPersist(Collections.singletonList(invalid), clara, output::add);

        assertTrue(usable.isEmpty());
        assertFalse(output.isEmpty());
        assertTrue(texts(output).stream().anyMatch(line -> line.contains("[不可用]") && line.contains("invalid")));
        assertEquals(AvailabilityLevelEnum.FAIL, levelOf(output, "invalid"));
    }

    /**
     * 每条结论在该模型检查结束后立即输出：探测第二个模型时，第一个模型的结果必须已经发出，
     * 不能等整批检查结束再一起输出。
     */
    @Test
    public void emitsEachResultBeforeProbingNextModel() throws Exception {
        Path clara = temporary.newFile("streaming.yml").toPath();
        List<Path> configs = Arrays.asList(config("deepseek-completion.yml"),
                config("deepseek-response.yml"), config("kimi-completion.yml"));
        List<AvailabilityLine> output = new ArrayList<>();
        List<String> emittedBeforeSecondProbe = new ArrayList<>();
        AtomicInteger probes = new AtomicInteger();
        IModelProbe probe = (path, alias, provider, chat, timeout) -> {
            if (probes.incrementAndGet() == 2) {
                emittedBeforeSecondProbe.addAll(texts(output));
            }
            return ModelCheckResult.available(path, alias, provider.getProvider().name(), chat.getModelId(), 8L);
        };

        new ModelAvailabilityManager(name -> "test-key", probe, 1000L)
                .checkAndPersist(configs, clara, output::add);

        assertEquals(3, probes.get());
        assertTrue("探测第二个模型前应已输出第一个模型的结果，实际输出：" + emittedBeforeSecondProbe,
                emittedBeforeSecondProbe.stream().anyMatch(line -> line.contains("deepseek-completion")));
        // 结果行（带「平台与模型」列）恰好 3 条，顺序与配置顺序一致；末尾的「默认模型」汇总行不计入
        List<String> rows = texts(output).stream()
                .filter(line -> line.contains("provider="))
                .collect(Collectors.toList());
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).contains("deepseek-completion"));
        assertTrue(rows.get(1).contains("deepseek-response"));
        assertTrue(rows.get(2).contains("kimi-completion"));
    }

    /**
     * 首次探测「等待首个响应超时」时按配置重试；重试成功则该模型判定为可用。
     */
    @Test
    public void retriesOnceAfterTimeout() throws Exception {
        Path clara = temporary.newFile("retry.yml").toPath();
        List<Path> configs = Collections.singletonList(config("deepseek-completion.yml"));
        AtomicInteger probes = new AtomicInteger();
        IModelProbe probe = (path, alias, provider, chat, timeout) -> probes.incrementAndGet() == 1
                ? ModelCheckResult.unavailable(path, alias, provider.getProvider().name(), chat.getModelId(),
                        "等待首个响应超时（60000ms）")
                : ModelCheckResult.available(path, alias, provider.getProvider().name(), chat.getModelId(), 120L);
        List<Path> usable = new ModelAvailabilityManager(name -> "test-key", probe, 60_000L, 1)
                .checkAndPersist(configs, clara, line -> { });

        assertEquals(2, probes.get());
        assertEquals(1, usable.size());
    }

    /**
     * 非超时类失败不重试：平台返回错误时重复请求不会改变结果。
     */
    @Test
    public void doesNotRetryNonTimeoutFailure() throws Exception {
        Path clara = temporary.newFile("no-retry.yml").toPath();
        List<Path> configs = Collections.singletonList(config("deepseek-completion.yml"));
        AtomicInteger probes = new AtomicInteger();
        IModelProbe probe = (path, alias, provider, chat, timeout) -> {
            probes.incrementAndGet();
            return ModelCheckResult.unavailable(path, alias, provider.getProvider().name(), chat.getModelId(),
                    "模型平台调用失败：平台 DEEPSEEK，返回 401：invalid key");
        };
        new ModelAvailabilityManager(name -> "test-key", probe, 60_000L, 1)
                .checkAndPersist(configs, clara, line -> { });

        assertEquals(1, probes.get());
    }

    /**
     * 取出输出行的纯文本，便于按文案断言。
     *
     * @param lines 输出行
     * @return 与输入一一对应的纯文本列表
     */
    private static List<String> texts(List<AvailabilityLine> lines) {
        return lines.stream().map(AvailabilityLine::getText).collect(Collectors.toList());
    }

    /**
     * 取指定别名对应输出行的状态级别。
     *
     * @param lines 输出行
     * @param alias 模型别名（出现在行文本中即命中）
     * @return 匹配到的级别；没有匹配行时为 null
     */
    private static AvailabilityLevelEnum levelOf(List<AvailabilityLine> lines, String alias) {
        return lines.stream()
                .filter(line -> line.getText().contains(alias))
                .map(AvailabilityLine::getLevel)
                .findFirst()
                .orElse(null);
    }

    private static Path config(String name) {
        return Paths.get("..", "config", "model-providers", name).normalize();
    }
}
