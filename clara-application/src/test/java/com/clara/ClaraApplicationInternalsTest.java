package com.clara;

import com.clara.adapter.console.CancelKeyMonitor;
import com.clara.adapter.console.ConsoleInput;
import com.clara.adapter.console.ConsoleView;
import com.clara.adapter.console.LaunchOptions;
import com.clara.adapter.console.SessionCommandHandler;
import com.clara.config.ModelRegistry;
import com.clara.core.interaction.IConversationControls;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.interaction.SessionCommandOutcomeEnum;
import com.clara.kernel.message.delta.TokenUsage;
import com.clara.mcp.api.IMcpClient;
import com.clara.mcp.api.IMcpClientFactory;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.model.embedding.HashingEmbeddingClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ClaraApplication} 内部流程单元测试：通过反射覆盖不依赖标准输入 / 子进程的决策与渲染逻辑
 * （模型选择、故障转移顺序、会话指令控制、图谱步骤渲染与主循环分发）。
 */
public class ClaraApplicationInternalsTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private ConsoleView view;
    private ApplicationBootstrap bootstrap;
    private ModelRegistry registry;
    private Path modelConfig;

    @Before
    public void setUp() throws Exception {
        modelConfig = writeConfig("alpha.yml", "alpha-model");
        registry = new ModelRegistry(Collections.singletonList(modelConfig), Collections.emptyMap());
        registry.select(modelConfig);
        view = new ConsoleView(folder.newFolder("logs-app").toPath(), false, "never");
        bootstrap = new ApplicationBootstrap(new HashingEmbeddingClient(1024), 1024, unusedFactory());
        setConversationId("coverage-session");
    }

    @After
    public void tearDown() {
        if (bootstrap != null) {
            bootstrap.close();
        }
        if (registry != null) {
            registry.close();
        }
        if (view != null) {
            view.close();
        }
    }

    /**
     * 路径与忽略大小写的命中判断。
     */
    @Test
    public void matchesCandidates() throws Exception {
        Path other = folder.newFile("beta.yml").toPath();
        assertTrue((Boolean) call("containsPath", new Class<?>[]{List.class, Path.class},
                Arrays.asList(modelConfig, other), modelConfig.toAbsolutePath()));
        assertFalse((Boolean) call("containsPath", new Class<?>[]{List.class, Path.class},
                Collections.singletonList(other), modelConfig));
        assertTrue((Boolean) call("containsIgnoreCase", new Class<?>[]{List.class, String.class},
                Arrays.asList("DEEPSEEK", "kimi"), "deepseek"));
        assertFalse((Boolean) call("containsIgnoreCase", new Class<?>[]{List.class, String.class},
                Arrays.asList("DEEPSEEK"), "kimi"));
    }

    /**
     * 用量趋势记录与新的会话标识。
     */
    @Test
    public void recordsUsageAndBuildsConversationId() throws Exception {
        call("recordUsageTrend", new Class<?>[]{ApplicationBootstrap.class, TokenUsage.class},
                bootstrap, null);
        call("recordUsageTrend", new Class<?>[]{ApplicationBootstrap.class, TokenUsage.class},
                bootstrap, new TokenUsage(2, 3, null, null));
        call("recordUsageTrend", new Class<?>[]{ApplicationBootstrap.class, TokenUsage.class},
                bootstrap, new TokenUsage(2, 3, 9, 1));
        String id = (String) call("newConversationId", new Class<?>[0]);
        assertNotNull(id);
        assertTrue(id.contains("-"));
    }

    /**
     * 首次选择（命令行指定）与重新选择（沿用 / 无候选）分支。
     */
    @Test
    public void selectsAndReselectsModel() throws Exception {
        Object chosen = call("selectModelConfig",
                new Class<?>[]{ConsoleView.class, BufferedReader.class, LaunchOptions.class, List.class},
                view, new BufferedReader(new StringReader("")), LaunchOptions.parse(new String[]{modelConfig.toString()}),
                Collections.singletonList(modelConfig));
        assertEquals(modelConfig.toAbsolutePath().normalize(), configPath(chosen).toAbsolutePath().normalize());

        Object previous = modelChoice(modelConfig, "上一轮");
        Object reselected = call("reselectModel",
                new Class<?>[]{ConsoleView.class, BufferedReader.class, LaunchOptions.class, List.class, previous.getClass()},
                view, new BufferedReader(new StringReader("")), LaunchOptions.parse(null),
                Collections.singletonList(modelConfig), previous);
        assertEquals(modelConfig.toAbsolutePath().normalize(), configPath(reselected).toAbsolutePath().normalize());

        Object noCandidates = call("reselectModel",
                new Class<?>[]{ConsoleView.class, BufferedReader.class, LaunchOptions.class, List.class, previous.getClass()},
                view, new BufferedReader(new StringReader("")), LaunchOptions.parse(null),
                Collections.emptyList(), previous);
        assertEquals(modelConfig.toAbsolutePath().normalize(), configPath(noCandidates).toAbsolutePath().normalize());

        Object fromOption = call("reselectModel",
                new Class<?>[]{ConsoleView.class, BufferedReader.class, LaunchOptions.class, List.class, previous.getClass()},
                view, new BufferedReader(new StringReader("")), LaunchOptions.parse(new String[]{modelConfig.toString()}),
                Collections.emptyList(), previous);
        assertEquals(modelConfig.toAbsolutePath().normalize(), configPath(fromOption).toAbsolutePath().normalize());
    }

    /**
     * 图谱步骤渲染：成功结果的首行/续行与异常兜底。
     */
    @Test
    public void rendersCodeGraphSteps() throws Exception {
        Class<?> stepType = Class.forName("com.clara.ClaraApplication$CodeGraphStep");
        Object success = step(stepType, "结果：已导入图谱\n细节一\n\n细节二");
        call("emitCodeGraphStep", new Class<?>[]{ConsoleView.class, String.class, stepType},
                view, "挂载检查", success);
        Object empty = step(stepType, "  \n ");
        call("emitCodeGraphStep", new Class<?>[]{ConsoleView.class, String.class, stepType},
                view, "导入检查", empty);
        Object failure = step(stepType, null);
        call("emitCodeGraphStep", new Class<?>[]{ConsoleView.class, String.class, stepType},
                view, "导入检查", failure);
        call("emitCodeGraph", new Class<?>[]{ConsoleView.class, String.class}, view, "直接输出");
    }

    /**
     * 会话级上下文控制：压缩、用量与新建对话。
     */
    @Test
    public void drivesConversationControls() throws Exception {
        IConversationControls controls = (IConversationControls) call("contextControls",
                new Class<?>[]{ConsoleView.class, ApplicationBootstrap.class, ModelRegistry.class,
                        com.clara.telemetry.TelemetryStore.class, com.clara.telemetry.TelemetryMetrics.class,
                        String.class},
                view, bootstrap, registry, null, null, null);
        controls.compressContext();
        controls.showUsage();
        controls.showHistory();
        controls.newConversation();
        call("printRecentHistory", new Class<?>[]{ConsoleView.class, ApplicationBootstrap.class,
                ModelRegistry.class, ConsoleInput.class}, view, bootstrap, registry, null);
        assertNotNull(controls);
    }

    /**
     * 主循环：空行跳过、指令处理、退出与重新初始化。
     */
    @Test
    public void dispatchesSessionLoop() throws Exception {
        try (CancelKeyMonitor monitor = CancelKeyMonitor.create(view)) {
            BufferedReader reader = new BufferedReader(new StringReader("\n/?\nexit\n"));
            SessionCommandHandler commands = new SessionCommandHandler(view, reader, registry,
                    Collections.singletonList(modelConfig), null);
            assertEquals(SessionCommandOutcomeEnum.EXIT, call("loop",
                    new Class<?>[]{ConsoleView.class, BufferedReader.class, ApplicationBootstrap.class,
                            ModelRegistry.class, List.class, SessionCommandHandler.class, CancelKeyMonitor.class},
                    view, reader, bootstrap, registry, Collections.singletonList(modelConfig), commands, monitor));

            BufferedReader reinitReader = new BufferedReader(new StringReader("/init\n"));
            SessionCommandHandler reinitCommands = new SessionCommandHandler(view, reinitReader, registry,
                    Collections.singletonList(modelConfig), null);
            assertEquals(SessionCommandOutcomeEnum.REINIT, call("loop",
                    new Class<?>[]{ConsoleView.class, BufferedReader.class, ApplicationBootstrap.class,
                            ModelRegistry.class, List.class, SessionCommandHandler.class, CancelKeyMonitor.class},
                    view, reinitReader, bootstrap, registry, Collections.singletonList(modelConfig), reinitCommands, monitor));

            BufferedReader eofReader = new BufferedReader(new StringReader(""));
            SessionCommandHandler eofCommands = new SessionCommandHandler(view, eofReader, registry,
                    Collections.singletonList(modelConfig), null);
            assertEquals(SessionCommandOutcomeEnum.EXIT, call("loop",
                    new Class<?>[]{ConsoleView.class, BufferedReader.class, ApplicationBootstrap.class,
                            ModelRegistry.class, List.class, SessionCommandHandler.class, CancelKeyMonitor.class},
                    view, eofReader, bootstrap, registry, Collections.singletonList(modelConfig), eofCommands, monitor));
        }
    }

    private static Object step(Class<?> stepType, String result) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("run".equals(method.getName())) {
                if (result == null) {
                    throw new IllegalStateException("脚本不可用");
                }
                return result;
            }
            return null;
        };
        return Proxy.newProxyInstance(stepType.getClassLoader(), new Class<?>[]{stepType}, handler);
    }

    private static Object modelChoice(Path path, String source) throws Exception {
        Class<?> type = Class.forName("com.clara.ClaraApplication$ModelChoice");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(Path.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(path, source);
    }

    private static Path configPath(Object choice) throws Exception {
        Method method = choice.getClass().getDeclaredMethod("configPath");
        method.setAccessible(true);
        return (Path) method.invoke(choice);
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = ClaraApplication.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw failure;
        }
    }

    private static void setConversationId(String value) throws Exception {
        java.lang.reflect.Field field = ClaraApplication.class.getDeclaredField("conversationId");
        field.setAccessible(true);
        field.set(null, value);
    }

    private static IMcpClientFactory unusedFactory() {
        return new IMcpClientFactory() {
            @Override
            public IMcpClient create(McpServerRegistration registration) {
                throw new IllegalStateException("not used in this test");
            }
        };
    }

    private Path writeConfig(String name, String modelId) throws Exception {
        Path file = folder.newFile(name).toPath();
        String yaml = "provider: " + ModelProviderEnum.DEEPSEEK.name() + "\n"
                + "credential:\n"
                + "  secretRef: deepseek\n"
                + "  apiKey: test-key\n"
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
                + "    apiProfile: RESPONSE\n";
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
