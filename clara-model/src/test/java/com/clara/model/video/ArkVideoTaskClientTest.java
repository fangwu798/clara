package com.clara.model.video;

import com.clara.core.media.VideoGenerationRequest;
import com.clara.core.media.VideoGenerationResult;
import com.clara.core.transport.IExecutionGuard;
import com.clara.core.transport.IHttpCall;
import com.clara.core.transport.IHttpTransport;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import com.clara.core.transport.sse.ISseEventListener;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.config.ProviderCapabilityConfiguration;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.credential.ProviderCredentialConfiguration;
import com.clara.kernel.http.TransportConfig;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import org.junit.Assume;
import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link ArkVideoTaskClient} 单元测试：提交请求体、任务轮询、视频地址提取与平台错误透传。
 */
public class ArkVideoTaskClientTest {

    @Test
    public void submitsPollsAndExtractsVideoUrl() {
        FakeTransport transport = new FakeTransport();
        transport.respond(json(200, "{\"id\":\"cgt-1\"}"));
        transport.respond(json(200, "{\"id\":\"cgt-1\",\"status\":\"running\"}"));
        transport.respond(json(200, "{\"id\":\"cgt-1\",\"status\":\"succeeded\","
                + "\"content\":{\"video_url\":\"https://example.com/out.mp4\"},"
                + "\"usage\":{\"total_tokens\":10}}"));

        try (ProviderConfiguration configuration = configuration()) {
            VideoGenerationResult result = new ArkVideoTaskClient(configuration, transport)
                    .generate(new VideoGenerationRequest("一只猫在草地上奔跑",
                            Collections.singletonList("https://example.com/ref.png"), null));

            assertEquals("cgt-1", result.getTaskId());
            assertEquals("succeeded", result.getStatus());
            assertEquals(Collections.singletonList("https://example.com/out.mp4"), result.getVideoUrls());
            assertEquals(10, result.getUsage().get("total_tokens"));
        }

        assertEquals(3, transport.requests.size());
        RawHttpRequest submit = transport.requests.get(0);
        assertEquals("POST", submit.getMethod());
        String body = new String(submit.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"model\":\"doubao-seedance-2-0-mini-260615\""));
        assertTrue(body.contains("\"type\":\"text\""));
        assertTrue(body.contains("--ratio 16:9"));
        assertTrue(body.contains("--duration 5"));
        assertTrue(body.contains("\"type\":\"image_url\""));
        assertTrue(body.contains("\"role\":\"first_frame\""));

        RawHttpRequest query = transport.requests.get(1);
        assertEquals("GET", query.getMethod());
        assertEquals("/api/v3/contents/generations/tasks/cgt-1", query.getEndpoint().getPath());
    }

    @Test
    public void surfacesPlatformErrorWithHttpStatus() {
        FakeTransport transport = new FakeTransport();
        transport.respond(json(403, "{\"error\":{\"code\":\"AccountOverdue\","
                + "\"message\":\"The request failed because your account has an overdue balance.\"}}"));

        try (ProviderConfiguration configuration = configuration()) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new ArkVideoTaskClient(configuration, transport)
                            .generate(new VideoGenerationRequest("一只猫", null, null)));

            assertTrue(failure.getMessage().contains("HTTP 403"));
            assertTrue(failure.getMessage().contains("overdue balance"));
        }
    }

    @Test
    public void reportsFailedTaskWithoutSleepingForever() {
        FakeTransport transport = new FakeTransport();
        transport.respond(json(200, "{\"id\":\"cgt-2\"}"));
        transport.respond(json(200, "{\"id\":\"cgt-2\",\"status\":\"failed\","
                + "\"error\":{\"code\":\"InvalidParameter\",\"message\":\"bad prompt\"}}"));

        try (ProviderConfiguration configuration = configuration()) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new ArkVideoTaskClient(configuration, transport)
                            .generate(new VideoGenerationRequest("一只猫", null, null)));

            assertTrue(failure.getMessage().contains("bad prompt"));
        }
    }

    @Test
    public void loadsVolcengineVideoCapabilityFromRepositoryConfig() throws Exception {
        Path classes = Paths.get(ArkVideoTaskClientTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path file = classes.getParent().getParent().getParent()
                .resolve("config").resolve("model-providers").resolve("volcengine-response.yml");
        Assume.assumeTrue("未找到平台配置文件: " + file.toAbsolutePath(), Files.isRegularFile(file));

        try (ProviderConfiguration configuration = new ProviderYamlConfigurationLoader().load(file)) {
            ModelConfig video = configuration.toConfig(CapabilityKindEnum.VIDEO);
            assertEquals("doubao-seedance-2-0-mini-260615", video.getModelId());
            assertTrue(configuration.getCapability(CapabilityKindEnum.VIDEO)
                    .requireTaskQueryEndpoint().toString().contains("id"));
            assertEquals(5, ((Number) configuration.getCapability(CapabilityKindEnum.VIDEO)
                    .getParameters().get("duration")).intValue());
        }
    }

    private static ProviderConfiguration configuration() {
        ProviderCredentialConfiguration credential = new ProviderCredentialConfiguration(
                "volcengine", null, "test-key".toCharArray());
        TransportConfig transport = new TransportConfig(
                Collections.singleton("https://ark.cn-beijing.volces.com"),
                1000, 1000, 1 << 20, 1 << 20, false);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pollIntervalMillis", 1);
        parameters.put("maxPollAttempts", 3);
        parameters.put("ratio", "16:9");
        parameters.put("duration", 5);
        ProviderCapabilityConfiguration video = new ProviderCapabilityConfiguration(
                URI.create("https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks"),
                URI.create("https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks/%7Bid%7D"),
                "doubao-seedance-2-0-mini-260615", "ark-v3-content-tasks", null, parameters, null);
        Map<CapabilityKindEnum, ProviderCapabilityConfiguration> capabilities = new LinkedHashMap<>();
        capabilities.put(CapabilityKindEnum.VIDEO, video);
        return new ProviderConfiguration(ModelProviderEnum.VOLCENGINE, credential, transport, capabilities);
    }

    private static RawHttpResponse json(int status, String body) {
        return new RawHttpResponse(status, Collections.<String, List<String>>emptyMap(),
                body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按顺序返回预置响应的假传输，并记录每次请求。
     */
    private static final class FakeTransport implements IHttpTransport {

        private final Deque<RawHttpResponse> responses = new ArrayDeque<>();
        private final List<RawHttpRequest> requests = new ArrayList<>();

        private void respond(RawHttpResponse response) {
            responses.add(response);
        }

        @Override
        public IHttpCall newCall(RawHttpRequest request, com.clara.core.transport.HttpTransportPolicy policy,
                IExecutionGuard guard) {
            return new IHttpCall() {
                private boolean cancelled;

                @Override
                public RawHttpResponse execute() {
                    requests.add(request);
                    return responses.removeFirst();
                }

                @Override
                public RawHttpResponse execute(ISseEventListener listener) {
                    return execute();
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }

                @Override
                public void close() {
                    // 测试用调用没有连接需要释放
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }
    }
}
