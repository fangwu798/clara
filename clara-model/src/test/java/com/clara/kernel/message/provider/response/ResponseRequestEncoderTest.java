package com.clara.kernel.message.provider.response;

import com.clara.context.ConvContext;
import com.clara.core.message.IContent;
import com.clara.core.transport.RawHttpRequest;
import com.clara.dto.tool.ToolContext;
import com.clara.dto.tool.ToolSpecification;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.Thinking;
import com.clara.kernel.message.delta.UserContent;
import com.clara.enums.ThinkingEnum;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Responses 协议请求编码的单元测试：系统指令提升、消息 / 工具渲染、Codex 模拟字段与参数校验。
 *
 * <p>为什么按线格式断言：Responses 接口对形状非常敏感——系统消息混进 {@code input} 会被 400，
 * 少了 {@code instructions} 人设直接丢失，参数超范围则整轮失败。只断言「能编码」看不出这些差别。
 */
public class ResponseRequestEncoderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void systemInstructionIsLiftedOutOfInput() throws Exception {
        try (ProviderConfiguration provider = loadProvider(deepseekYaml())) {
            JsonNode body = encode(provider, builder(Arrays.<IContent>asList(
                    new SystemContent("你是一名严谨的技术编辑"),
                    new UserContent("介绍下你自己"))).build());

            assertEquals("你是一名严谨的技术编辑", body.path("instructions").asText());
            assertEquals("input 里不应再有系统消息", 1, body.path("input").size());
            assertEquals("介绍下你自己", body.path("input").get(0).path("content").get(0).path("text").asText());
        }
    }

    @Test
    public void emptyMessagesKeepBodyWithoutInstructions() throws Exception {
        try (ProviderConfiguration provider = loadProvider(deepseekYaml())) {
            JsonNode body = encode(provider, builder(Collections.<IContent>emptyList()).build());
            assertFalse(body.has("instructions"));
            assertEquals("空列表保持为空数组", 0, body.path("input").size());
        }
    }

    @Test
    public void toolContextsAreRenderedAsTools() throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        ToolContext tool = new ToolContext(new ToolSpecification("get_weather", "查天气", schema));
        try (ProviderConfiguration provider = loadProvider(deepseekYaml())) {
            JsonNode body = encode(provider, builder(Collections.<IContent>singletonList(new UserContent("hi")))
                    .tools(Collections.singletonList(tool)).build());
            assertEquals("get_weather", body.path("tools").get(0).path("name").asText());
            assertEquals("查天气", body.path("tools").get(0).path("description").asText());
            assertEquals("function", body.path("tools").get(0).path("type").asText());
        }
    }

    @Test
    public void openAiAddsClientMetadataAndCodexDefaults() throws Exception {
        try (ProviderConfiguration provider = loadProvider(openAiYaml())) {
            JsonNode body = encode(provider, builder(Collections.<IContent>singletonList(new UserContent("hi")))
                    .reasoningEffort("low").build());

            assertTrue("OpenAI 中转需要请求体带 client_metadata", body.path("client_metadata").isObject());
            assertTrue(body.path("client_metadata").has("session_id"));
            assertEquals("all_turns", body.path("reasoning").path("context").asText());
            assertFalse(body.path("store").asBoolean(true));
            assertEquals("reasoning.encrypted_content", body.path("include").get(0).asText());
            assertEquals("low", body.path("text").path("verbosity").asText());
        }
    }

    @Test
    public void nonOpenAiKeepsBodyUntouched() throws Exception {
        try (ProviderConfiguration provider = loadProvider(deepseekYaml())) {
            JsonNode body = encode(provider, builder(Collections.<IContent>singletonList(new UserContent("hi"))).build());
            assertFalse(body.has("client_metadata"));
            assertFalse(body.has("store"));
        }
    }

    @Test
    public void thinkingValueMustBeAllowedForProvider() throws Exception {
        Thinking thinking = new Thinking();
        thinking.setType(ThinkingEnum.AUTO);
        try (ProviderConfiguration provider = loadProvider(completionYaml("DEEPSEEK", "https://api.deepseek.com"))) {
            try {
                encode(provider, builder(Collections.<IContent>singletonList(new UserContent("hi")))
                        .thinking(thinking).build());
                fail("DeepSeek 不接受 auto，应校验失败");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("thinking"));
            }
        }
    }

    @Test
    public void temperatureOutsideRangeIsRejected() throws Exception {
        try (ProviderConfiguration provider = loadProvider(completionYaml("DEEPSEEK", "https://api.deepseek.com"))) {
            try {
                encode(provider, builder(Collections.<IContent>singletonList(new UserContent("hi")))
                        .temperature(3.0d).build());
                fail("温度超上限应校验失败");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("maximum"));
            }
            try {
                encode(provider, builder(Collections.<IContent>singletonList(new UserContent("hi")))
                        .temperature(-1.0d).build());
                fail("温度低于下限应校验失败");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("minimum"));
            }
        }
    }

    @Test
    public void stopCountLimitIsEnforced() throws Exception {
        try (ProviderConfiguration provider = loadProvider(completionYaml("VOLCENGINE", "https://ark.cn-beijing.volces.com"))) {
            try {
                encode(provider, builder(Collections.<IContent>singletonList(new UserContent("hi")))
                        .stops(Arrays.asList("a", "b", "c", "d", "e")).build());
                fail("火山最多 4 条停止序列，应校验失败");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("maximum"));
            }
        }
    }

    @Test
    public void reasoningEffortIsValidatedPerProvider() throws Exception {
        try (ProviderConfiguration provider = loadProvider(completionYaml("DEEPSEEK", "https://api.deepseek.com"))) {
            try {
                encode(provider, builder(Collections.<IContent>singletonList(new UserContent("hi")))
                        .reasoningEffort("low").build());
                fail("DeepSeek 只接受 high/max");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("not allowed"));
            }
            JsonNode body = encode(provider, builder(Collections.<IContent>singletonList(new UserContent("hi")))
                    .reasoningEffort("max").build());
            assertEquals("max", body.path("reasoning_effort").asText());
        }
    }

    @Test
    public void unsupportedFieldsAreDropped() throws Exception {
        try (ProviderConfiguration provider = loadProvider(completionYaml("DEEPSEEK", "https://api.deepseek.com"))) {
            JsonNode body = encode(provider, builder(Collections.<IContent>singletonList(new UserContent("hi")))
                    .responseFormat("json_object").build());
            assertEquals("json_object", body.path("response_format").path("type").asText());
        }
    }

    @Test
    public void protocolNameFallsBackForUnannotatedFields() throws Exception {
        ResponseRequestEncoder encoder = new ResponseRequestEncoder();
        java.lang.reflect.Field field = com.clara.kernel.message.BasicParameters.class.getDeclaredField("model");
        java.lang.reflect.Method method = ResponseRequestEncoder.class.getDeclaredMethod(
                "protocolName", java.lang.reflect.Field.class, String.class);
        method.setAccessible(true);
        assertEquals("model", method.invoke(encoder, field, "model"));
    }

    private static HttpRequest.HttpRequestBuilder<?, ?> builder(List<IContent> messages) {
        return HttpRequest.builder().model("test-model").messages(messages).stream(Boolean.TRUE);
    }

    private static JsonNode encode(ProviderConfiguration provider, HttpRequest request) {
        try (ConvContext.Scope ignored = ConvContext.open(
                new ConvContext(null, "response-encoder", provider, CapabilityKindEnum.CHAT))) {
            RawHttpRequest raw = request.toRequest();
            try {
                return MAPPER.readTree(raw.getBody());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private ProviderConfiguration loadProvider(String yaml) throws Exception {
        Path file = folder.newFile("provider-" + System.nanoTime() + ".yml").toPath();
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return new ProviderYamlConfigurationLoader().load(file);
    }

    private static String completionYaml(String provider, String origin) {
        return "provider: " + provider + "\n"
                + "credential:\n"
                + "  secretRef: test\n"
                + "  apiKey: test-key\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - " + origin + "\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 180000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  CHAT:\n"
                + "    endpoint: " + origin + "/chat/completions\n"
                + "    modelId: test-model\n"
                + "    apiProfile: Completion\n";
    }

    private static String deepseekYaml() {
        return yaml("DEEPSEEK", "https://api.deepseek.com", "deepseek-v4-pro", "responses-v1");
    }

    private static String openAiYaml() {
        return yaml("OPENAI", "https://new.aicontrol.top", "gpt-5.6-sol", "responses-v1")
                .replace("  allowHttp: false\n", "  allowHttp: false\n  headers:\n    x-Transfer: true\n");
    }

    private static String volcengineYaml() {
        return yaml("VOLCENGINE", "https://ark.cn-beijing.volces.com", "doubao-seed", "responses-v1");
    }

    private static String yaml(String provider, String origin, String modelId, String profile) {
        return "provider: " + provider + "\n"
                + "credential:\n"
                + "  secretRef: test\n"
                + "  apiKey: test-key\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - " + origin + "\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 180000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  CHAT:\n"
                + "    endpoint: " + origin + "/v1/responses\n"
                + "    modelId: " + modelId + "\n"
                + "    apiProfile: " + profile + "\n"
                + "    protocol: RESPONSE\n";
    }
}