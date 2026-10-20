package com.clara.adapter.console;

import com.clara.config.ModelRegistry;
import com.clara.core.interaction.IConversationControls;
import com.clara.enums.AvailabilityLevelEnum;
import com.clara.enums.interaction.SessionCommandOutcomeEnum;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 控制台适配层：启动参数、命令分发、日志/颜色开关与用量趋势渲染。
 */
public class ApplicationConsoleTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private ConsoleView view;
    private ModelRegistry registry;
    private Path modelConfig;

    @Before
    public void setUp() throws Exception {
        modelConfig = writeConfig("alpha.yml", "alpha-model");
        registry = new ModelRegistry(Collections.singletonList(modelConfig), Collections.emptyMap());
        registry.select(modelConfig);
        view = new ConsoleView(folder.newFolder("logs").toPath(), false, "never");
    }

    @After
    public void tearDown() {
        if (view != null) {
            view.close();
        }
        if (registry != null) {
            registry.close();
        }
    }

    /**
     * 启动参数：日志开关、颜色开关与首个配置文件参数。
     */
    @Test
    public void parsesLaunchOptions() {
        LaunchOptions empty = LaunchOptions.parse(null);
        assertNull(empty.configFile());
        assertFalse(empty.fullLogging());
        assertNull(empty.forcedColor());

        LaunchOptions options = LaunchOptions.parse(new String[]{" ", "-v", "--no-color", "model.yml", "--verbose", "extra.yml"});
        assertEquals("model.yml", options.configFile());
        assertTrue(options.fullLogging());
        assertEquals("never", options.forcedColor());

        assertTrue(LaunchOptions.parse(new String[]{"--full"}).fullLogging());
        assertTrue(LaunchOptions.parse(new String[]{"--verbose"}).fullLogging());
    }

    /**
     * 会话命令：退出、初始化、未知输入与帮助。
     */
    @Test
    public void dispatchesSessionCommands() {
        CountingControls controls = new CountingControls();
        SessionCommandHandler handler = new SessionCommandHandler(view,
                new BufferedReader(new StringReader("1\n")), registry, Collections.singletonList(modelConfig), controls);

        assertEquals(SessionCommandOutcomeEnum.EXIT, handler.handle("exit"));
        assertEquals(SessionCommandOutcomeEnum.EXIT, handler.handle("QUIT"));
        assertEquals(SessionCommandOutcomeEnum.EXIT, handler.handle("/exit"));
        assertEquals(SessionCommandOutcomeEnum.REINIT, handler.handle("/init"));
        assertEquals(SessionCommandOutcomeEnum.INPUT, handler.handle("hello"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/?"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/help"));

        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/compress"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/compact"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/new"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/usage"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/tokens"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/graph"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/graph import"));
        assertEquals(2, controls.compressed.get());
        assertEquals(1, controls.newConversation.get());
        assertEquals(2, controls.usage.get());
        assertEquals(1, controls.showGraph.get());
        assertEquals(1, controls.importGraph.get());
        assertEquals(0, controls.unsupported.get());
    }

    /**
     * 上下文控制缺失时只提示不支持，不抛异常。
     */
    @Test
    public void handlesMissingControls() {
        SessionCommandHandler handler = new SessionCommandHandler(view,
                new BufferedReader(new StringReader("")), registry, null, null);
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/compress"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/new"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/usage"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/graph"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/graph import"));
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/model"));
    }

    /**
     * 日志与颜色开关在两种状态间切换。
     */
    @Test
    public void togglesLogAndColors() {
        SessionCommandHandler handler = new SessionCommandHandler(view,
                new BufferedReader(new StringReader("")), registry, null, null);
        boolean colors = view.isColorsEnabled();
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/color"));
        assertEquals(!colors, view.isColorsEnabled());
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/color"));
        assertEquals(colors, view.isColorsEnabled());

        boolean full = view.isFullLogging();
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/log"));
        assertEquals(!full, view.isFullLogging());
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/verbose"));
        assertEquals(full, view.isFullLogging());
    }

    /**
     * 模型切换：单候选直接选择并更新注册表。
     */
    @Test
    public void switchesModelWithSingleCandidate() {
        SessionCommandHandler handler = new SessionCommandHandler(view,
                new BufferedReader(new StringReader("")), registry, Collections.singletonList(modelConfig), null);
        assertEquals(SessionCommandOutcomeEnum.HANDLED, handler.handle("/model"));
        assertEquals("alpha", registry.currentAlias());
        assertEquals("alpha-model", registry.currentConfiguration().toConfig(
                com.clara.enums.CapabilityKindEnum.CHAT).getModelId());
    }

    /**
     * 控制台基础输出、颜色映射与关闭。
     */
    @Test
    public void writesConsoleOutput() {
        assertEquals(ConsoleView.ANSI_GREEN, ConsoleView.colorOf(AvailabilityLevelEnum.OK));
        assertEquals(ConsoleView.ANSI_RED, ConsoleView.colorOf(AvailabilityLevelEnum.FAIL));
        assertEquals(ConsoleView.ANSI_YELLOW, ConsoleView.colorOf(AvailabilityLevelEnum.WARN));
        assertNull(ConsoleView.colorOf(null));

        view.println("line");
        view.println();
        view.emit("plain");
        view.emit("colored", ConsoleView.ANSI_GRAY);
        view.logUserInput("hello");
        view.printHints(Collections.singletonList("my-flow"));
        assertNotNull(view.newObserver((toolName, arguments) -> "purpose"));
    }

    /**
     * 用量趋势：无数据提示、采样渲染与重置。
     */
    @Test
    public void rendersUsageTrend() {
        UsageTrendChart chart = new UsageTrendChart();
        assertTrue(chart.render().contains("暂无趋势数据"));
        chart.addSample(10L);
        chart.addSample(-5L);
        chart.addSample(20L);
        String rendered = chart.render();
        assertTrue(rendered.contains("累计 token 趋势"));
        assertTrue(rendered.contains("共 3 个采样点"));
        assertTrue(rendered.contains("末次累计 30 tokens"));
        chart.reset();
        assertTrue(chart.render().contains("暂无趋势数据"));
    }

    private Path writeConfig(String name, String modelId) throws Exception {
        Path file = folder.newFile(name).toPath();
        String yaml = "provider: DEEPSEEK\n"
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
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static final class CountingControls implements IConversationControls {
        private final AtomicInteger compressed = new AtomicInteger();
        private final AtomicInteger newConversation = new AtomicInteger();
        private final AtomicInteger usage = new AtomicInteger();
        private final AtomicInteger showGraph = new AtomicInteger();
        private final AtomicInteger importGraph = new AtomicInteger();
        private final AtomicInteger unsupported = new AtomicInteger();

        @Override
        public void compressContext() {
            compressed.incrementAndGet();
        }

        @Override
        public void newConversation() {
            newConversation.incrementAndGet();
        }

        @Override
        public void showUsage() {
            usage.incrementAndGet();
        }

        @Override
        public void showCodeGraph() {
            showGraph.incrementAndGet();
        }

        @Override
        public void importCodeGraph() {
            importGraph.incrementAndGet();
        }
    }
}