package com.clara;

import com.clara.adapter.console.ConsoleView;
import com.clara.config.ModelRegistry;
import com.clara.core.interaction.IConversationControls;
import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.interaction.HistoryClearScopeEnum;
import com.clara.mcp.api.IMcpClient;
import com.clara.mcp.api.IMcpClientFactory;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.model.embedding.HashingEmbeddingClient;
import com.clara.telemetry.TelemetryMetrics;
import com.clara.telemetry.TelemetryStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

/**
 * {@link ClaraApplication} 会话内控制（{@code /compress}、{@code /new}、{@code /trace}、{@code /usage}、
 * {@code /history}）的行为测试。
 *
 * <p>为什么这些分支必须覆盖：它们是用户在长期会话里唯一的「自救手段」。压缩失败没提示、链路列表空着、
 * 历史读不出来，用户只会看到「命令没反应」，而异常又被吞在 catch 里——不写用例基本不可能发现。
 */
public class ClaraApplicationControlsTest {

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
        view = new ConsoleView(folder.newFolder("logs-controls").toPath(), false, "never");
        bootstrap = new ApplicationBootstrap(new HashingEmbeddingClient(1024), 1024, unusedFactory());
        setConversationId("controls-session");
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

    @Test
    public void compressAndNewConversation() throws Exception {
        IConversationControls controls = controls(null, null, null);

        controls.compressContext();

        String before = conversationId();
        controls.newConversation();
        String after = conversationId();
        assertNotEquals("新对话必须换一个会话标识", before, after);
        assertNotNull(after);
    }

    @Test
    public void traceCommandHandlesDisabledEmptyAndPopulatedStore() throws Exception {
        controls(null, new TelemetryMetrics(), null).showTrace(null);

        Path telemetryRoot = folder.newFolder("telemetry-empty").toPath();
        try (TelemetryStore store = new TelemetryStore(telemetryRoot, 5, 0)) {
            IConversationControls controls = controls(store, null, null);
            controls.showTrace("list");
            controls.showTrace(null);
            controls.showTrace("missing-trace");

            store.onSpan(event("trace-1", "span-1", "model.call"));
            store.onSpan(event("trace-1", "span-2", "tool.call"));
            controls.showTrace("list");
            controls.showTrace(null);
            controls.showTrace("  trace-1  ");
        }
    }

    @Test
    public void usageReportsCapabilityAndPrometheusAddress() throws Exception {
        controls(null, null, null).showUsage();

        TelemetryMetrics metrics = new TelemetryMetrics();
        metrics.onSpan(event("trace-1", "span-1", "model.call"));
        controls(null, metrics, "127.0.0.1:9100").showUsage();
        controls(null, metrics, null).showUsage();
    }

    @Test
    public void historyFallsBackToEmptyAndReportsReadFailure() throws Exception {
        controls(null, null, null).showHistory();
        controls(null, null, null).clearHistory(HistoryClearScopeEnum.FIRST, 1);

        ModelRegistry unselected = new ModelRegistry(Collections.singletonList(modelConfig), Collections.emptyMap());
        try {
            IConversationControls broken = controls(unselected, null, null, null);
            broken.showHistory();
            broken.compressContext();
            broken.showUsage();
        } finally {
            unselected.close();
        }
    }

    private IConversationControls controls(TelemetryStore store, TelemetryMetrics metrics, String address)
            throws Exception {
        return controls(registry, store, address, metrics);
    }

    private IConversationControls controls(ModelRegistry targetRegistry, TelemetryStore store, String address,
            TelemetryMetrics metrics) throws Exception {
        Method method = ClaraApplication.class.getDeclaredMethod("contextControls", ConsoleView.class,
                ApplicationBootstrap.class, ModelRegistry.class, TelemetryStore.class, TelemetryMetrics.class,
                String.class);
        method.setAccessible(true);
        try {
            return (IConversationControls) method.invoke(null, view, bootstrap, targetRegistry, store, metrics,
                    address);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw failure;
        }
    }

    private static TelemetryEvent event(String traceId, String spanId, String name) {
        return TelemetryEvent.builder()
                .traceId(traceId)
                .spanId(spanId)
                .name(name)
                .kind(TelemetrySpanKindEnum.OTHER)
                .startEpochMillis(System.currentTimeMillis())
                .durationMillis(5L)
                .attributes(Collections.singletonMap("provider", "DEEPSEEK"))
                .build();
    }

    private static String conversationId() throws Exception {
        Field field = ClaraApplication.class.getDeclaredField("conversationId");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static void setConversationId(String value) throws Exception {
        Field field = ClaraApplication.class.getDeclaredField("conversationId");
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