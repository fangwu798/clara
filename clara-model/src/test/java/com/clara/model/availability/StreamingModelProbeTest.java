package com.clara.model.availability;

import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link StreamingModelProbe} 的端到端测试：本地 HTTP 服务按场景返回 SSE 分片，
 * 覆盖「首个正文判定可用」「只有思维链仍可用」「平台错误」「无正文」「超时」「连接失败」。
 */
public class StreamingModelProbeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private HttpServer server;
    private volatile String mode = "text";
    private final AtomicInteger requests = new AtomicInteger();
    private ProviderConfiguration configuration;
    private Path configPath;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requests.incrementAndGet();
                read(exchange.getRequestBody());
                try {
                    respond(exchange);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        configPath = folder.newFile("probe.yml").toPath();
        // 可用性探测测试必须自包含：CI 上没有 CLARA_DEEPSEEK_API_KEY，
        // 依赖环境变量会让鉴权头组装失败，六个用例会被误判为模型不可用。
        String yaml = "provider: DEEPSEEK\n"
                + "credential:\n"
                + "  secretRef: deepseek\n"
                + "  apiKey: test-key\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - http://127.0.0.1:" + port + "\n"
                + "  connectTimeoutMillis: 2000\n"
                + "  readTimeoutMillis: 2000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: true\n"
                + "capabilities:\n"
                + "  CHAT:\n"
                + "    endpoint: http://127.0.0.1:" + port + "/chat\n"
                + "    modelId: probe-model\n"
                + "    apiProfile: Completion\n";
        Files.write(configPath, yaml.getBytes(StandardCharsets.UTF_8));
        configuration = new ProviderYamlConfigurationLoader().load(configPath);
    }

    @After
    public void tearDown() {
        if (configuration != null) {
            configuration.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void firstContentDeltaMarksModelAvailable() {
        mode = "text";

        ModelCheckResult result = probe(3000L);

        assertTrue(result.isAvailable());
        assertTrue(result.getFirstCharacterMillis() >= 0L);
        assertEquals("DEEPSEEK", result.getProvider());
        assertEquals("probe-model", result.getModel());
    }

    @Test
    public void reasoningOnlyResponseIsStillAvailableWithExplanation() {
        mode = "reasoning";

        ModelCheckResult result = probe(3000L);

        assertTrue(result.isAvailable());
        assertTrue(result.getDetail().contains("思维链"));
    }

    @Test
    public void terminalResponseWithoutContentIsUnavailable() {
        mode = "empty";

        ModelCheckResult result = probe(3000L);

        assertFalse(result.isAvailable());
        assertTrue(result.getDetail().contains("没有正文"));
        assertTrue(result.getDetail().contains("LENGTH"));
    }

    @Test
    public void platformErrorIsReported() {
        mode = "error";

        ModelCheckResult result = probe(3000L);

        assertFalse(result.isAvailable());
        assertTrue(result.getDetail().toLowerCase().contains("500"));
    }

    @Test
    public void transientServerErrorIsRetriedOnce() {
        mode = "flaky";
        requests.set(0);

        ModelCheckResult result = probe(3000L);

        assertTrue("一次上游抖动不应把模型判成不可用", result.isAvailable());
        assertEquals(2, requests.get());
    }

    @Test
    public void slowServerTimesOut() {
        mode = "slow";

        ModelCheckResult result = probe(150L);

        assertFalse(result.isAvailable());
        assertTrue(result.getDetail().contains("超时"));
    }

    @Test
    public void connectionFailureIsReportedAsUnavailable() {
        server.stop(0);
        server = null;

        ModelCheckResult result = probe(1000L);

        assertFalse(result.isAvailable());
        assertTrue(result.getDetail().length() > 0);
    }

    private ModelCheckResult probe(long timeoutMillis) {
        ModelConfig chat = configuration.toConfig(CapabilityKindEnum.CHAT);
        return new StreamingModelProbe().probe(configPath, "probe", configuration, chat, timeoutMillis);
    }

    private void respond(HttpExchange exchange) throws IOException, InterruptedException {
        if ("slow".equals(mode)) {
            Thread.sleep(1500L);
        }
        byte[] body;
        String contentType;
        int status = 200;
        if ("error".equals(mode) || ("flaky".equals(mode) && requests.get() == 1)) {
            status = 500;
            contentType = "application/json";
            body = "{\"error\":{\"message\":\"boom\",\"type\":\"server_error\"}}".getBytes(StandardCharsets.UTF_8);
        } else if ("reasoning".equals(mode)) {
            contentType = "text/event-stream";
            body = ("data: {\"id\":\"1\",\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n"
                    + "data: {\"id\":\"1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        } else if ("empty".equals(mode)) {
            contentType = "text/event-stream";
            body = ("data: {\"id\":\"1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        } else {
            contentType = "text/event-stream";
            body = "data: {\"id\":\"1\",\"choices\":[{\"delta\":{\"content\":\"1\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void read(InputStream input) throws IOException {
        try (InputStream source = input) {
            byte[] buffer = new byte[1024];
            while (source.read(buffer) >= 0) {
                // 读取并丢弃请求体
            }
        }
    }
}
