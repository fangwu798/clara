package com.clara.kernel.message.provider.messages;

import com.clara.context.ConvContext;
import com.clara.core.message.IContent;
import com.clara.core.transport.RawHttpRequest;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.dto.tool.ToolContext;
import com.clara.dto.tool.ToolResult;
import com.clara.dto.tool.ToolSpecification;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.RoleEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.message.delta.UserVisionContent;
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
import static org.junit.Assert.assertTrue;

/**
 * {@link MessagesRequestEncoder} 的单元测试：工具选择、多模态内容、工具结果合并与 max_tokens 兜底。
 *
 * <p>这些分支都直接决定请求能否被 Messages API 接受；错误形状要么被平台 400 拒绝，要么静默丢内容，
 * 因此按线格式逐项断言，而不是只断言「能编码出 JSON」。
 */
public class MessagesRequestEncoderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void toolChoiceKeywordsMapToAnthropicTypes() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            assertEquals("none", body(provider, messages(), "none", null).path("tool_choice").path("type").asText());
            assertEquals("auto", body(provider, messages(), "auto", null).path("tool_choice").path("type").asText());
            assertEquals("any", body(provider, messages(), "required", null).path("tool_choice").path("type").asText());
            assertEquals("any", body(provider, messages(), "any", null).path("tool_choice").path("type").asText());
            assertFalse(body(provider, messages(), "  ", null).has("tool_choice"));
        }
    }

    @Test
    public void toolChoiceObjectPrefersFunctionName() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            JsonNode choice = body(provider, messages(),
                    "{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}", null).path("tool_choice");

            assertEquals("tool", choice.path("type").asText());
            assertEquals("get_weather", choice.path("name").asText());
        }
    }

    @Test
    public void toolChoiceObjectFallsBackToRawType() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            JsonNode choice = body(provider, messages(), "{\"type\":\"auto\"}", null).path("tool_choice");

            assertEquals("auto", choice.path("type").asText());
            assertFalse(choice.has("name"));
        }
    }

    @Test
    public void malformedToolChoiceObjectIsDropped() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            assertFalse(body(provider, messages(), "{not-json", null).has("tool_choice"));
            assertFalse(body(provider, messages(), "{\"type\":123}", null).has("tool_choice"));
        }
    }

    @Test
    public void disableParallelToolUseAddsAutoChoiceWhenAbsent() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            JsonNode choice = body(provider, messages(), null, Boolean.FALSE).path("tool_choice");

            assertEquals("auto", choice.path("type").asText());
            assertTrue(choice.path("disable_parallel_tool_use").asBoolean());
        }
    }

    @Test
    public void disableParallelToolUseKeepsExplicitChoice() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            JsonNode choice = body(provider, messages(), "none", Boolean.FALSE).path("tool_choice");

            assertEquals("none", choice.path("type").asText());
            assertTrue(choice.path("disable_parallel_tool_use").asBoolean());
        }
    }

    @Test
    public void visionContentRendersDataUriAndUrlImages() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            IContent vision = new UserVisionContent("看图", Arrays.asList(
                    "data:image/jpeg;base64,/9j/AA",
                    "https://example.com/a.png",
                    "data:image/png;base64"));

            JsonNode content = body(provider, Collections.singletonList(vision), null, null)
                    .path("messages").get(0).path("content");

            assertEquals(3, content.size());
            assertEquals("text", content.get(0).path("type").asText());
            assertEquals("base64", content.get(1).path("source").path("type").asText());
            assertEquals("image/jpeg", content.get(1).path("source").path("media_type").asText());
            assertEquals("/9j/AA", content.get(1).path("source").path("data").asText());
            assertEquals("url", content.get(2).path("source").path("type").asText());
            assertEquals("https://example.com/a.png", content.get(2).path("source").path("url").asText());
        }
    }

    @Test
    public void nonMapPartsAndBrokenImageReferencesAreSkipped() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            message.put("content", Arrays.asList(
                    "raw-string",
                    part("image_url", "not-a-map"),
                    imagePart(""),
                    imagePart("data:image/png,no-semicolon"),
                    part("text", "保留我")));

            JsonNode content = body(provider, Collections.singletonList(new MapContent(message)), null, null)
                    .path("messages").get(0).path("content");

            assertEquals(1, content.size());
            assertEquals("text", content.get(0).path("type").asText());
            assertEquals("保留我", content.get(0).path("text").asText());
        }
    }

    @Test
    public void emptyAssistantMessageIsDropped() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            JsonNode messages = body(provider,
                    Arrays.asList(new UserContent("hi"), new AssistantContent("")), null, null).path("messages");

            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).path("role").asText());
            assertEquals("hi", messages.get(0).path("content").asText());
        }
    }

    @Test
    public void toolCallArgumentsAreParsedDefensively() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            ToolCallContent calls = new ToolCallContent(Arrays.asList(
                    call("c1", ""), call("c2", "not-json"), call("c3", "[1,2]"),
                    call("c4", "{\"city\":\"上海\"}")));

            JsonNode blocks = body(provider, Collections.singletonList(calls), null, null)
                    .path("messages").get(0).path("content");

            assertEquals(4, blocks.size());
            assertTrue(blocks.get(0).path("input").isObject());
            assertEquals(0, blocks.get(0).path("input").size());
            assertTrue(blocks.get(1).path("input").isObject());
            assertTrue(blocks.get(2).path("input").isObject());
            assertEquals("上海", blocks.get(3).path("input").path("city").asText());
        }
    }

    @Test
    public void consecutiveToolResultsShareOneUserMessage() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            JsonNode messages = body(provider, Arrays.asList(
                    new UserContent("查询"),
                    new ToolResult("c1", "{\"a\":1}"),
                    new ToolResult("c2", null)), null, null).path("messages");

            assertEquals(2, messages.size());
            JsonNode blocks = messages.get(1).path("content");
            assertEquals("user", messages.get(1).path("role").asText());
            assertEquals(2, blocks.size());
            assertEquals("c1", blocks.get(0).path("tool_use_id").asText());
            assertEquals("", blocks.get(1).path("content").asText());
        }
    }

    @Test
    public void systemInstructionsAreJoinedAndBlankOnesSkipped() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            JsonNode body = body(provider, Arrays.asList(
                    new SystemContent("第一段"), new SystemContent("  "), new SystemContent("第二段"),
                    new UserContent("hi")), null, null);

            assertEquals("第一段\n\n第二段", body.path("system").asText());
            assertEquals(1, body.path("messages").size());
        }
    }

    @Test
    public void maxTokensFallsBackTo4096WhenCapabilityDoesNotConfigureIt() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(false))) {
            HttpRequest request = builder(messages(), null, null, null).build();

            JsonNode body = encode(provider, request);

            assertEquals(4096, body.path("max_tokens").asInt());
        }
    }

    @Test
    public void explicitMaxTokensIsKept() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            HttpRequest request = builder(messages(), null, null, 512).build();

            assertEquals(512, encode(provider, request).path("max_tokens").asInt());
        }
    }

    @Test
    public void toolsAreDeclaredWithInputSchema() throws Exception {
        try (ProviderConfiguration provider = loadProvider(anthropicYaml(true))) {
            HttpRequest request = builder(messages(), null, null, 256)
                    .tools(Collections.singletonList(new ToolContext(
                            new ToolSpecification("get_weather", "查天气", weatherSchema()))))
                    .build();

            JsonNode tool = encode(provider, request).path("tools").get(0);

            assertEquals("get_weather", tool.path("name").asText());
            assertEquals("object", tool.path("input_schema").path("type").asText());
            assertFalse(tool.has("type"));
        }
    }

    private static List<IContent> messages() {
        return Collections.<IContent>singletonList(new UserContent("hi"));
    }

    private static ToolCall call(String id, String arguments) {
        ToolCallFunction function = new ToolCallFunction();
        function.setName("get_weather");
        function.setArguments(arguments);
        ToolCall call = new ToolCall();
        call.setId(id);
        call.setType("function");
        call.setFunction(function);
        return call;
    }

    private static Map<String, Object> part(String type, Object value) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", type);
        part.put("image_url", value);
        part.put("text", value);
        return part;
    }

    private static Map<String, Object> imagePart(String url) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("url", url);
        return part("image_url", reference);
    }

    private JsonNode body(ProviderConfiguration provider, List<IContent> messages, String toolChoice,
            Boolean parallel) {
        HttpRequest request = builder(messages, toolChoice, parallel, null).build();
        return encode(provider, request);
    }

    private static HttpRequest.HttpRequestBuilder<?, ?> builder(List<IContent> messages, String toolChoice,
            Boolean parallel, Integer maxTokens) {
        HttpRequest.HttpRequestBuilder<?, ?> builder = HttpRequest.builder()
                .model("claude-opus-4-8")
                .messages(messages)
                .stream(Boolean.TRUE);
        if (toolChoice != null) {
            builder.toolChoice(toolChoice);
        }
        if (parallel != null) {
            builder.parallelToolCalls(parallel);
        }
        if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        return builder;
    }

    private static JsonNode encode(ProviderConfiguration provider, HttpRequest request) {
        try (ConvContext.Scope ignored = ConvContext.open(
                new ConvContext(null, "messages-encoder", provider, CapabilityKindEnum.CHAT))) {
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

    private static Map<String, Object> weatherSchema() {
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("city", city);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("city"));
        return schema;
    }

    private static String anthropicYaml(boolean withMaxTokens) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("provider: ANTHROPIC\n")
                .append("credential:\n")
                .append("  secretRef: anthropic\n")
                .append("  apiKey: test-key\n")
                .append("transport:\n")
                .append("  allowedOrigins:\n")
                .append("    - https://yurouter.cn\n")
                .append("  connectTimeoutMillis: 10000\n")
                .append("  readTimeoutMillis: 600000\n")
                .append("  maxRequestBytes: 8388608\n")
                .append("  maxResponseBytes: 33554432\n")
                .append("  allowHttp: false\n")
                .append("capabilities:\n")
                .append("  CHAT:\n")
                .append("    endpoint: https://yurouter.cn/v1/messages\n")
                .append("    modelId: claude-opus-4-8\n")
                .append("    apiProfile: messages-v1\n")
                .append("    protocol: MESSAGES\n")
                .append("    parameters:\n")
                .append("      apiVersion: \"2023-06-01\"\n");
        if (withMaxTokens) {
            yaml.append("      maxTokens: 32000\n");
        }
        return yaml.toString();
    }

    /**
     * 用任意线格式消息验证编码器的防御分支（非 Map 内容、畸形图片引用等）。
     */
    private static final class MapContent implements IContent {

        private final Map<String, Object> message;

        private MapContent(Map<String, Object> message) {
            this.message = message;
        }

        @Override
        public RoleEnum getRoleType() {
            return RoleEnum.USER;
        }

        @Override
        public Map<String, Object> toCompletionMessage() {
            return message;
        }

        @Override
        public List<Map<String, Object>> toResponseInputItems() {
            return new ArrayList<>();
        }
    }
}