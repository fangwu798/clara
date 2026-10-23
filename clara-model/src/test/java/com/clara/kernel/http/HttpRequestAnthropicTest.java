package com.clara.kernel.http;

import com.clara.context.ConvContext;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import com.clara.dto.model.StreamEvent;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.dto.tool.ToolContext;
import com.clara.dto.tool.ToolSpecification;
import com.clara.dto.tool.ToolResult;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.FinishReasonEnum;
import com.clara.enums.StreamEventEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.message.provider.ProviderResponseDecoder;
import com.clara.kernel.message.provider.StreamAssembler;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Anthropic Messages 协议的离线编解码测试。
 *
 * <p>覆盖请求侧的系统指令提升、必填 {@code max_tokens}、工具声明形状与鉴权头，响应侧的
 * {@code content[]} 块解析与流式工具分片拼装。
 */
public class HttpRequestAnthropicTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void encodesMessagesShapeAndDropsUndocumentedFields() throws Exception {
        try (ProviderConfiguration provider = loadAnthropic()) {
            RawHttpRequest raw = encode(provider, request(Boolean.TRUE, 256));
            JsonNode body = MAPPER.readTree(raw.getBody());

            assertEquals("claude-opus-4-8", body.path("model").asText());
            assertEquals("你是助手", body.path("system").asText());
            assertEquals(256, body.path("max_tokens").asInt());
            assertEquals(0.7d, body.path("temperature").asDouble(), 0.0001d);
            assertEquals(0.8d, body.path("top_p").asDouble(), 0.0001d);
            assertEquals("END", body.path("stop_sequences").get(0).asText());
            assertTrue(body.path("stream").asBoolean());

            JsonNode messages = body.path("messages");
            assertEquals(3, messages.size());
            assertEquals("user", messages.get(0).path("role").asText());
            assertEquals("你好", messages.get(0).path("content").asText());

            assertEquals("assistant", messages.get(1).path("role").asText());
            JsonNode toolUse = messages.get(1).path("content").get(0);
            assertEquals("tool_use", toolUse.path("type").asText());
            assertEquals("call-1", toolUse.path("id").asText());
            assertEquals("get_weather", toolUse.path("name").asText());
            assertEquals("上海", toolUse.path("input").path("city").asText());

            assertEquals("user", messages.get(2).path("role").asText());
            JsonNode toolResult = messages.get(2).path("content").get(0);
            assertEquals("tool_result", toolResult.path("type").asText());
            assertEquals("call-1", toolResult.path("tool_use_id").asText());

            JsonNode tool = body.path("tools").get(0);
            assertEquals("get_weather", tool.path("name").asText());
            assertEquals("object", tool.path("input_schema").path("type").asText());
            assertFalse(tool.has("type"));
            assertFalse(tool.has("function"));
            assertFalse(tool.has("parameters"));

            assertEquals("any", body.path("tool_choice").path("type").asText());
            assertFalse(body.has("parallel_tool_calls"));
            assertFalse(body.has("response_format"));
            assertFalse(body.has("thinking"));
            assertFalse(body.has("stream_options"));
            assertFalse(body.has("frequency_penalty"));
            assertFalse(body.has("presence_penalty"));
            assertFalse(body.has("logprobs"));
            assertFalse(body.has("user_id"));

            assertEquals("test-key", raw.getHeaders().get("x-api-key"));
            assertEquals("2023-06-01", raw.getHeaders().get("anthropic-version"));
            assertFalse(raw.getHeaders().containsKey("Authorization"));
            assertEquals("https://yurouter.cn/v1/messages", raw.getEndpoint().toString());
        }
    }

    @Test
    public void fillsMaxTokensFromCapabilityParametersWhenMissing() throws Exception {
        try (ProviderConfiguration provider = loadAnthropic()) {
            JsonNode body = MAPPER.readTree(encode(provider, request(Boolean.FALSE, null)).getBody());
            assertEquals(32000, body.path("max_tokens").asInt());
        }
    }

    @Test
    public void decodesContentBlocksAndUsage() throws Exception {
        String json = "{"
                + "\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-4-8\","
                + "\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"先想一下\"},"
                + "{\"type\":\"text\",\"text\":\"你好，我是 Claude。\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"上海\"}}"
                + "],"
                + "\"stop_reason\":\"tool_use\","
                + "\"usage\":{\"input_tokens\":21,\"output_tokens\":8,\"cache_read_input_tokens\":5}"
                + "}";
        RawHttpResponse raw = new RawHttpResponse(200, Collections.emptyMap(), json.getBytes(StandardCharsets.UTF_8));

        HttpResponse response;
        try (ProviderConfiguration provider = loadAnthropic();
                ConvContext.Scope ignored = ConvContext.open(
                        new ConvContext(null, "anthropic-decode", provider, CapabilityKindEnum.CHAT))) {
            response = ProviderResponseDecoder.decode(raw, HttpResponse.class);
        }

        assertEquals("你好，我是 Claude。", response.getContent());
        assertEquals("先想一下", response.getReasoning());
        assertEquals(FinishReasonEnum.TOOL_CALLS, response.getFinishReason());
        assertEquals(Integer.valueOf(21), response.getInputTokens());
        assertEquals(Integer.valueOf(8), response.getOutputTokens());
        assertEquals(Integer.valueOf(5), response.getCachedTokens());
        assertEquals("msg_1", response.getId());
        assertNotNull(response.getToolCalls());
        assertEquals(1, response.getToolCalls().size());
        ToolCall call = response.getToolCalls().get(0);
        assertEquals("toolu_1", call.getId());
        assertEquals("get_weather", call.getFunction().getName());
        assertEquals("{\"city\":\"上海\"}", call.getFunction().getArguments());
    }

    @Test
    public void assemblesStreamingTextAndToolArguments() throws Exception {
        try (ProviderConfiguration provider = loadAnthropic();
                ConvContext.Scope ignored = ConvContext.open(
                        new ConvContext(null, "anthropic-stream", provider, CapabilityKindEnum.CHAT))) {
            StreamAssembler<HttpResponse> assembler = ProviderResponseDecoder.streamAssembler(HttpResponse.class);
            List<StreamEvent> toolEvents = new ArrayList<>();

            accept(assembler, toolEvents, "message_start",
                    "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\",\"model\":\"claude-opus-4-8\","
                            + "\"content\":[],\"usage\":{\"input_tokens\":21,\"output_tokens\":1}}}");
            accept(assembler, toolEvents, "content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
            accept(assembler, toolEvents, "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}");
            accept(assembler, toolEvents, "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}");
            accept(assembler, toolEvents, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
            accept(assembler, toolEvents, "content_block_start",
                    "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                            + "\"id\":\"toolu_2\",\"name\":\"get_weather\",\"input\":{}}}");
            accept(assembler, toolEvents, "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\","
                            + "\"partial_json\":\"{\\\"city\\\":\"}}");
            accept(assembler, toolEvents, "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\","
                            + "\"partial_json\":\"\\\"上海\\\"}\"}}");
            accept(assembler, toolEvents, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");
            accept(assembler, toolEvents, "message_delta",
                    "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                            + "\"usage\":{\"output_tokens\":12}}");
            accept(assembler, toolEvents, "message_stop", "{\"type\":\"message_stop\"}");

            HttpResponse response = assembler.result();
            assertEquals("Hello", response.getContent());
            assertEquals(FinishReasonEnum.TOOL_CALLS, response.getFinishReason());
            assertEquals("msg_2", response.getId());
            assertEquals(Integer.valueOf(21), response.getInputTokens());
            assertEquals(Integer.valueOf(12), response.getOutputTokens());
            assertEquals(Integer.valueOf(33), response.getTotalTokens());
            assertNotNull(response.getToolCalls());
            assertEquals(1, response.getToolCalls().size());
            assertEquals("{\"city\":\"上海\"}", response.getToolCalls().get(0).getFunction().getArguments());
            assertTrue(assembler.isTerminated());

            assertFalse(toolEvents.isEmpty());
            JsonNode fragment = MAPPER.readTree(toolEvents.get(toolEvents.size() - 1).getPayload());
            assertEquals(1, fragment.get(0).path("index").asInt());
            assertEquals("toolu_2", fragment.get(0).path("id").asText());
            assertEquals("get_weather", fragment.get(0).path("function").path("name").asText());
        }
    }

    private static void accept(StreamAssembler<HttpResponse> assembler, List<StreamEvent> toolEvents,
            String event, String data) {
        for (StreamEvent streamEvent : assembler.accept(event, data)) {
            if (streamEvent.getType() == StreamEventEnum.TOOL_ARGUMENT_DELTA) {
                toolEvents.add(streamEvent);
            }
        }
    }

    private static HttpRequest request(Boolean stream, Integer maxTokens) {
        ToolCall call = new ToolCall();
        call.setId("call-1");
        call.setType("function");
        ToolCallFunction function = new ToolCallFunction();
        function.setName("get_weather");
        function.setArguments("{\"city\":\"上海\"}");
        call.setFunction(function);

        HttpRequest.HttpRequestBuilder<?, ?> builder = HttpRequest.builder()
                .model("claude-opus-4-8")
                .messages(Arrays.asList(
                        new SystemContent("你是助手"),
                        new UserContent("你好"),
                        new ToolCallContent(Collections.singletonList(call)),
                        new ToolResult("call-1", "{\"weather\":\"晴\"}")))
                .temperature(0.7d)
                .topP(0.8d)
                .responseFormat("json_object")
                .frequencyPenalty(0.2d)
                .presencePenalty(0.1d)
                .toolChoice("required")
                .parallelToolCalls(Boolean.TRUE)
                .logprobs(Boolean.TRUE)
                .topLogprobs(2)
                .userId("user-1")
                .stops(Collections.singletonList("END"))
                .stream(stream)
                .includeUsage(Boolean.TRUE)
                .tools(Collections.singletonList(new ToolContext(
                        new ToolSpecification("get_weather", "查询指定城市的天气", weatherSchema()))));
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        return builder.build();
    }

    private static RawHttpRequest encode(ProviderConfiguration provider, HttpRequest request) {
        try (ConvContext.Scope ignored = ConvContext.open(
                new ConvContext(null, "anthropic-encode", provider, CapabilityKindEnum.CHAT))) {
            return request.toRequest();
        }
    }

    private ProviderConfiguration loadAnthropic() throws Exception {
        Path file = folder.newFile("anthropic.yml").toPath();
        Files.write(file, anthropicYaml().getBytes(StandardCharsets.UTF_8));
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

    private static String anthropicYaml() {
        return "provider: ANTHROPIC\n"
                + "credential:\n"
                + "  secretRef: anthropic\n"
                + "  apiKey: test-key\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - https://yurouter.cn\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 600000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  CHAT:\n"
                + "    endpoint: https://yurouter.cn/v1/messages\n"
                + "    modelId: claude-opus-4-8\n"
                + "    apiProfile: messages-v1\n"
                + "    protocol: MESSAGES\n"
                + "    parameters:\n"
                + "      apiVersion: \"2023-06-01\"\n"
                + "      maxTokens: 32000\n";
    }
}
