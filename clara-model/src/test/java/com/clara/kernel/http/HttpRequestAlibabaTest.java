package com.clara.kernel.http;

import com.clara.context.ConvContext;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import com.clara.dto.tool.ToolContext;
import com.clara.dto.tool.ToolSpecification;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.FinishReasonEnum;
import com.clara.enums.ThinkingEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.message.delta.Thinking;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.message.provider.ProviderResponseDecoder;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 阿里云百炼（DashScope / 通义千问）OpenAI 兼容协议的离线编解码测试。
 *
 * <p>字段口径来自官方 OpenAI 兼容-Chat 文档与深度思考文档：支持
 * {@code messages} / {@code temperature} / {@code top_p} / {@code presence_penalty} / {@code max_tokens} /
 * {@code stop} / {@code tools} / {@code stream} / {@code stream_options.include_usage} /
 * {@code response_format}，并用 {@code enable_thinking} 控制思考模式；官方参数表没有的字段应被丢弃。
 */
public class HttpRequestAlibabaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void encodesSupportedFieldsAndDropsUndocumentedOnes() throws Exception {
        try (ProviderConfiguration provider = loadAlibaba()) {
            RawHttpRequest raw = encode(provider, request(ThinkingEnum.DISABLED, Boolean.TRUE));
            JsonNode body = MAPPER.readTree(raw.getBody());

            assertEquals("qwen3.8-max", body.path("model").asText());
            assertEquals("user", body.path("messages").get(0).path("role").asText());
            assertEquals("你好", body.path("messages").get(0).path("content").asText());
            assertEquals(0.7d, body.path("temperature").asDouble(), 0.0001d);
            assertEquals(0.8d, body.path("top_p").asDouble(), 0.0001d);
            assertEquals(256, body.path("max_tokens").asInt());
            assertEquals(0.1d, body.path("presence_penalty").asDouble(), 0.0001d);
            assertEquals("json_object", body.path("response_format").path("type").asText());
            assertEquals("END", body.path("stop").get(0).asText());
            assertTrue(body.path("stream").asBoolean());
            assertTrue(body.path("stream_options").path("include_usage").asBoolean());
            assertFalse(body.path("enable_thinking").asBoolean(true));
            assertEquals("function", body.path("tools").get(0).path("type").asText());
            assertEquals("get_weather", body.path("tools").get(0).path("function").path("name").asText());

            assertFalse(body.has("thinking"));
            assertFalse(body.has("max_completion_tokens"));
            assertFalse(body.has("reasoning_effort"));
            assertFalse(body.has("frequency_penalty"));
            assertFalse(body.has("tool_choice"));
            assertFalse(body.has("parallel_tool_calls"));
            assertFalse(body.has("logprobs"));
            assertFalse(body.has("top_logprobs"));
            assertFalse(body.has("service_tier"));
            assertFalse(body.has("user_id"));

            assertEquals("Bearer test-key", raw.getHeaders().get("Authorization"));
            assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                    raw.getEndpoint().toString());
        }
    }

    @Test
    public void mapsThinkingToEnableThinkingAndOmitsAuto() throws Exception {
        try (ProviderConfiguration provider = loadAlibaba()) {
            JsonNode enabled = MAPPER.readTree(encode(provider, request(ThinkingEnum.ENABLED, Boolean.FALSE)).getBody());
            assertTrue(enabled.path("enable_thinking").asBoolean());

            JsonNode auto = MAPPER.readTree(encode(provider, request(ThinkingEnum.AUTO, Boolean.FALSE)).getBody());
            assertFalse(auto.has("enable_thinking"));
            assertFalse(auto.has("thinking"));
        }
    }

    @Test
    public void omitsStreamOptionsWhenNotStreaming() throws Exception {
        try (ProviderConfiguration provider = loadAlibaba()) {
            JsonNode body = MAPPER.readTree(encode(provider, request(ThinkingEnum.DISABLED, Boolean.FALSE)).getBody());

            assertFalse(body.path("stream").asBoolean());
            assertFalse(body.has("stream_options"));
        }
    }

    @Test
    public void decodesCompletionResponseAndCachedTokens() throws Exception {
        try (ProviderConfiguration provider = loadAlibaba()) {
            String json = "{"
                    + "\"id\":\"chatcmpl-1\","
                    + "\"model\":\"qwen3.8-max\","
                    + "\"choices\":[{"
                    + "  \"index\":0,"
                    + "  \"finish_reason\":\"stop\","
                    + "  \"message\":{"
                    + "    \"role\":\"assistant\","
                    + "    \"content\":\"你好，我是千问。\","
                    + "    \"reasoning_content\":\"先思考一下\""
                    + "  }"
                    + "}],"
                    + "\"usage\":{"
                    + "  \"prompt_tokens\":21,"
                    + "  \"completion_tokens\":8,"
                    + "  \"total_tokens\":29,"
                    + "  \"prompt_tokens_details\":{\"cached_tokens\":5}"
                    + "}"
                    + "}";
            RawHttpResponse raw = new RawHttpResponse(200, Collections.emptyMap(),
                    json.getBytes(StandardCharsets.UTF_8));

            HttpResponse response;
            try (ConvContext.Scope ignored = ConvContext.open(
                    new ConvContext(null, "alibaba-decode", provider, CapabilityKindEnum.CHAT))) {
                response = ProviderResponseDecoder.decode(raw, HttpResponse.class);
            }

            assertEquals("你好，我是千问。", response.getContent());
            assertEquals("先思考一下", response.getReasoning());
            assertEquals(Integer.valueOf(21), response.getInputTokens());
            assertEquals(Integer.valueOf(8), response.getOutputTokens());
            assertEquals(Integer.valueOf(29), response.getTotalTokens());
            assertEquals(Integer.valueOf(5), response.getCachedTokens());
            assertEquals(FinishReasonEnum.STOP, response.getFinishReason());
        }
    }

    private static HttpRequest request(ThinkingEnum thinking, Boolean stream) {
        Thinking mode = new Thinking();
        mode.setType(thinking);
        return HttpRequest.builder()
                .model("qwen3.8-max")
                .messages(Collections.singletonList(new UserContent("你好")))
                .thinking(mode)
                .temperature(0.7d)
                .topP(0.8d)
                .maxTokens(256)
                .maxCompletionTokens(128)
                .reasoningEffort("high")
                .frequencyPenalty(0.2d)
                .presencePenalty(0.1d)
                .responseFormat("json_object")
                .stops(Collections.singletonList("END"))
                .toolChoice("required")
                .parallelToolCalls(Boolean.TRUE)
                .logprobs(Boolean.TRUE)
                .topLogprobs(2)
                .serviceTier("auto")
                .userId("user-1")
                .stream(stream)
                .includeUsage(Boolean.TRUE)
                .tools(Collections.singletonList(new ToolContext(
                        new ToolSpecification("get_weather", "查询指定城市的天气", weatherSchema()))))
                .build();
    }

    private static RawHttpRequest encode(ProviderConfiguration provider, HttpRequest request) {
        try (ConvContext.Scope ignored = ConvContext.open(
                new ConvContext(null, "alibaba-encode", provider, CapabilityKindEnum.CHAT))) {
            return request.toRequest();
        }
    }

    private ProviderConfiguration loadAlibaba() throws Exception {
        Path file = folder.newFile("alibaba-completion.yml").toPath();
        Files.write(file, alibabaYaml().getBytes(StandardCharsets.UTF_8));
        return new ProviderYamlConfigurationLoader().load(file);
    }

    private static Map<String, Object> weatherSchema() {
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("type", "string");
        city.put("description", "城市名称");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("city", city);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("city"));
        return schema;
    }

    private static String alibabaYaml() {
        return "provider: ALIBABA\n"
                + "credential:\n"
                + "  secretRef: alibaba\n"
                + "  apiKey: test-key\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - https://dashscope.aliyuncs.com\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 600000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  CHAT:\n"
                + "    endpoint: https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions\n"
                + "    modelId: qwen3.8-max\n"
                + "    apiProfile: Completion\n"
                + "    protocol: COMPLETION\n"
                + "    parameters:\n"
                + "      maxTokens: 131072\n";
    }
}