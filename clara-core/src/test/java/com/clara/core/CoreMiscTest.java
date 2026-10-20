package com.clara.core;

import com.clara.core.memory.ConversationMemoryText;
import com.clara.core.media.VideoGenerationRequest;
import com.clara.core.media.VideoGenerationResult;
import com.clara.core.message.IContent;
import com.clara.core.tool.ITool;
import com.clara.dto.skill.PromptDefinition;
import com.clara.dto.skill.PromptSegment;
import com.clara.dto.skill.ToolMethod;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.enums.HttpTransportErrorKindEnum;
import com.clara.enums.MediaTypeEnum;
import com.clara.enums.RoleEnum;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.exception.HttpTransportException;
import com.clara.media.MediaAsset;
import com.clara.media.MediaMetadata;
import com.clara.media.MediaSafetyAssessment;
import com.clara.utils.MapUtils;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * core 零散契约：工具接口默认渲染、媒体资产、技能 DTO、错误对象与工具函数。
 */
public class CoreMiscTest {

    /**
     * ITool 默认方法按两种协议渲染声明。
     */
    @Test
    public void rendersToolDeclarations() {
        Map<String, Object> parameters = Collections.singletonMap("type", "object");
        ITool tool = new ITool() {
            @Override
            public String name() {
                return "search";
            }

            @Override
            public String description() {
                return "search docs";
            }

            @Override
            public Map<String, Object> parameters() {
                return parameters;
            }

            @Override
            public String invoke(String argumentsJson) {
                return argumentsJson;
            }
        };
        assertEquals("search", tool.name());
        assertEquals("search docs", tool.description());
        assertEquals(parameters, tool.parameters());
        assertEquals("{}", tool.invoke("{}"));

        Map<String, Object> completion = tool.toCompletionDeclaration();
        assertEquals("function", completion.get("type"));
        assertEquals("search", ((Map<?, ?>) completion.get("function")).get("name"));
        Map<String, Object> response = tool.toResponseDeclaration();
        assertEquals("search", response.get("name"));
        assertEquals(Boolean.TRUE, response.get("strict"));

        ITool noDescription = new ITool() {
            @Override
            public String name() {
                return "n";
            }

            @Override
            public String description() {
                return null;
            }

            @Override
            public Map<String, Object> parameters() {
                return Collections.emptyMap();
            }

            @Override
            public String invoke(String argumentsJson) {
                return null;
            }
        };
        assertFalse(((Map<?, ?>) noDescription.toCompletionDeclaration().get("function")).containsKey("description"));
        assertFalse(noDescription.toResponseDeclaration().containsKey("description"));
    }

    /**
     * 媒体资产构建、元数据校验与安全评估。
     */
    @Test
    public void buildsMediaTypes() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("k", "v");
        MediaAsset asset = MediaAsset.builder("a1", MediaTypeEnum.IMAGE, "image/png", 10L)
                .sha256("hash")
                .storageUri("file:///tmp/a.png")
                .sourceUri("https://example.com/a.png")
                .width(10)
                .height(20)
                .durationMs(30L)
                .ownerTenantId("t1")
                .createdAt(100L)
                .expiresAt(200L)
                .metadata(metadata)
                .build();
        assertEquals("a1", asset.getAssetId());
        assertEquals(MediaTypeEnum.IMAGE, asset.getMediaType());
        assertEquals("image/png", asset.getMimeType());
        assertEquals(10L, asset.getSize());
        assertEquals("hash", asset.getSha256());
        assertEquals("file:///tmp/a.png", asset.getStorageUri());
        assertEquals("https://example.com/a.png", asset.getSourceUri());
        assertEquals(Integer.valueOf(10), asset.getWidth());
        assertEquals(Integer.valueOf(20), asset.getHeight());
        assertEquals(Long.valueOf(30L), asset.getDurationMs());
        assertEquals("t1", asset.getOwnerTenantId());
        assertEquals(100L, asset.getCreatedAt());
        assertEquals(Long.valueOf(200L), asset.getExpiresAt());
        metadata.put("k", "changed");
        assertEquals("v", asset.getMetadata().get("k"));
        assertThrows(UnsupportedOperationException.class, () -> asset.getMetadata().put("x", "y"));

        assertThrows(IllegalArgumentException.class,
                () -> MediaAsset.builder(" ", MediaTypeEnum.IMAGE, "image/png", 1L).sha256("h")
                        .storageUri("u").ownerTenantId("t").build());
        assertThrows(IllegalArgumentException.class,
                () -> MediaAsset.builder("a", null, "image/png", 1L).sha256("h")
                        .storageUri("u").ownerTenantId("t").build());
        assertThrows(IllegalArgumentException.class,
                () -> MediaAsset.builder("a", MediaTypeEnum.IMAGE, " ", 1L).sha256("h")
                        .storageUri("u").ownerTenantId("t").build());
        assertThrows(IllegalArgumentException.class,
                () -> MediaAsset.builder("a", MediaTypeEnum.IMAGE, "image/png", -1L).sha256("h")
                        .storageUri("u").ownerTenantId("t").build());
        assertThrows(IllegalArgumentException.class,
                () -> MediaAsset.builder("a", MediaTypeEnum.IMAGE, "image/png", 1L).sha256("h")
                        .storageUri("u").build());
        assertEquals("v", MediaAsset.builder("a", MediaTypeEnum.IMAGE, "image/png", 1L).sha256("h")
                .storageUri("u").ownerTenantId("t").metadata(Collections.singletonMap(" ", "v")).build()
                .getMetadata().get(" "));

        MediaMetadata metadataValue = new MediaMetadata(10, 20, 30L);
        assertEquals(Integer.valueOf(10), metadataValue.getWidth());
        assertEquals(Integer.valueOf(20), metadataValue.getHeight());
        assertEquals(Long.valueOf(30L), metadataValue.getDurationMs());
        assertNull(new MediaMetadata(null, null, null).getWidth());
        assertThrows(IllegalArgumentException.class, () -> new MediaMetadata(10, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaMetadata(null, 20, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaMetadata(0, 20, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaMetadata(10, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaMetadata(10, 20, -1L));

        MediaSafetyAssessment allowed = MediaSafetyAssessment.allowed();
        assertTrue(allowed.isAllowed());
        assertNull(allowed.getReasonCode());
    }

    /**
     * 技能 DTO：工具方法、提示词分段与定义。
     */
    @Test
    public void buildsSkillDtos() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("m", "v");
        ToolMethod method = new ToolMethod("id", "name", ScriptTypeEnum.JAVASCRIPT, "desc",
                "{}", "script.js", metadata);
        assertEquals("id", method.getId());
        assertEquals("name", method.getName());
        assertEquals(ScriptTypeEnum.JAVASCRIPT, method.getType());
        assertEquals("desc", method.getDescription());
        assertEquals("{}", method.getParameters());
        assertEquals("script.js", method.getScript());
        assertEquals("v", method.getMetadata().get("m"));

        assertThrows(IllegalArgumentException.class,
                () -> new ToolMethod(" ", "n", ScriptTypeEnum.JAVASCRIPT, "d", null, "s", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolMethod("i", "", ScriptTypeEnum.JAVASCRIPT, "d", null, "s", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolMethod("i", "n", null, "d", null, "s", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolMethod("i", "n", ScriptTypeEnum.JAVASCRIPT, " ", null, "s", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolMethod("i", "n", ScriptTypeEnum.JAVASCRIPT, "d", null, "", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolMethod("i", "n", ScriptTypeEnum.JAVASCRIPT, "d", null, "s",
                        Collections.singletonMap("k", null)));

        PromptSegment segment = new PromptSegment("system", "you are helpful");
        assertEquals("system", segment.getRole());
        assertEquals("you are helpful", segment.getContent());
        assertThrows(IllegalArgumentException.class, () -> new PromptSegment(" ", "c"));
        assertThrows(IllegalArgumentException.class, () -> new PromptSegment("r", ""));

        PromptDefinition definition = new PromptDefinition("p1", "desc", Collections.singletonList(segment));
        assertEquals("p1", definition.getId());
        assertEquals("desc", definition.getDescription());
        assertEquals(1, definition.getSegments().size());
        assertThrows(UnsupportedOperationException.class, () -> definition.getSegments().add(segment));
        assertThrows(IllegalArgumentException.class, () -> new PromptDefinition(" ", null, Collections.singletonList(segment)));
        assertThrows(IllegalArgumentException.class, () -> new PromptDefinition("p", null, null));
        assertThrows(IllegalArgumentException.class, () -> new PromptDefinition("p", null, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> new PromptDefinition("p", null,
                Collections.singletonList(null)));
    }

    /**
     * 视频生成请求与结果：提示词校验、参考图/用量只读。
     */
    @Test
    public void buildsVideoTypes() {
        List<String> references = new ArrayList<>();
        references.add("https://example.com/a.png");
        AtomicReference<String> progress = new AtomicReference<>();
        VideoGenerationRequest request = new VideoGenerationRequest("  a cat  ", references, progress::set);
        assertEquals("a cat", request.getPrompt());
        assertEquals(1, request.getReferences().size());
        request.getProgress().accept("submitted");
        assertEquals("submitted", progress.get());
        assertThrows(UnsupportedOperationException.class, () -> request.getReferences().add("x"));
        assertNull(new VideoGenerationRequest("p", null, null).getProgress());
        assertThrows(IllegalArgumentException.class, () -> new VideoGenerationRequest(" ", null, null));

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("tokens", 1);
        VideoGenerationResult result = new VideoGenerationResult("t1", "SUCCEEDED",
                Collections.singletonList("https://example.com/v.mp4"), usage);
        assertEquals("t1", result.getTaskId());
        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(1, result.getVideoUrls().size());
        assertEquals(1, result.getUsage().get("tokens"));
        assertThrows(UnsupportedOperationException.class, () -> result.getVideoUrls().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> result.getUsage().put("x", 1));
    }

    /**
     * 错误对象：默认文案、拒绝把错误码当消息、取消错误与传输异常分类。
     */
    @Test
    public void buildsErrors() {
        ClaraError error = ClaraError.builder("MODEL_TIMEOUT", ErrorCategoryEnum.TIMEOUT)
                .retryable(true)
                .traceId("trace")
                .provider("DEEPSEEK")
                .providerCode("429")
                .retryAfterMillis(1000L)
                .safeDetails(Collections.singletonMap("k", "v"))
                .build();
        assertEquals("MODEL_TIMEOUT", error.getCode());
        assertEquals(ErrorCategoryEnum.TIMEOUT, error.getCategory());
        assertTrue(error.isRetryable());
        assertEquals("trace", error.getTraceId());
        assertEquals("DEEPSEEK", error.getProvider());
        assertEquals("429", error.getProviderCode());
        assertEquals(Long.valueOf(1000L), error.getRetryAfterMillis());
        assertEquals("v", error.getSafeDetails().get("k"));
        assertEquals("MODEL_TIMEOUT", error.getSafeMessage());

        ClaraException exception = new ClaraException(error);
        assertEquals(error, exception.getError());
        assertTrue(exception.getMessage().contains("MODEL_TIMEOUT"));

        ClaraError cancelled = ClaraError.cancelled("t");
        assertEquals("INVOCATION_CANCELLED", cancelled.getCode());
        assertEquals(ErrorCategoryEnum.CANCELLED, cancelled.getCategory());
        assertFalse(cancelled.isRetryable());

        assertTrue(new HttpTransportException(HttpTransportErrorKindEnum.TIMEOUT, "t").isRetryable());
        assertTrue(HttpTransportException.ioError(new RuntimeException("x")).isRetryable());
        assertFalse(HttpTransportException.endpointDenied().isRetryable());
        assertFalse(HttpTransportException.requestTooLarge().isRetryable());
        assertFalse(HttpTransportException.responseTooLarge().isRetryable());
        assertEquals(HttpTransportErrorKindEnum.PROTOCOL_ERROR,
                HttpTransportException.protocolError("p").getKind());
        assertEquals(HttpTransportErrorKindEnum.CANCELLED, HttpTransportException.cancelled().getKind());
        assertEquals(HttpTransportErrorKindEnum.CLOSED, HttpTransportException.closed().getKind());
        assertThrows(IllegalArgumentException.class,
                () -> new HttpTransportException(null, "x"));
    }

    /**
     * 通用 Map 工具：空值归一、只读标量映射与非空写入。
     */
    @Test
    public void manipulatesMaps() {
        Map<String, Object> source = new HashMap<>();
        source.put("a", 1);
        Map<String, Object> copied = MapUtils.copyMap(source);
        source.put("a", 2);
        assertEquals(1, copied.get("a"));
        assertTrue(MapUtils.copyMap(null).isEmpty());

        Map<String, Object> nested = new HashMap<>();
        nested.put("nested", Collections.singletonMap("x", 1));
        assertThrows(IllegalArgumentException.class, () -> MapUtils.unmodifiableScalarMap(nested));
        Map<String, Object> scalar = MapUtils.unmodifiableScalarMap(Collections.singletonMap("a", 1));
        assertEquals(1, scalar.get("a"));
        assertThrows(UnsupportedOperationException.class, () -> scalar.put("b", 2));
        assertTrue(MapUtils.unmodifiableScalarMap(null).isEmpty());

        Map<String, String> target = new HashMap<>();
        MapUtils.putIfNotNull(target, "k", null);
        assertFalse(target.containsKey("k"));
        MapUtils.putIfNotNull(target, "k", "v");
        assertEquals("v", target.get("k"));
    }

    /**
     * 会话入库文本：角色前缀、多模态正文、工具调用摘要与长度截断。
     */
    @Test
    public void rendersConversationMemoryText() {
        assertNull(ConversationMemoryText.of(null));
        assertNull(ConversationMemoryText.of(content("assistant", null)));
        assertEquals("user: hello", ConversationMemoryText.of(content("user", "hello")));
        assertEquals("message: body", ConversationMemoryText.of(content(null, "body")));

        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Collections.singletonMap("type", "input_text"));
        Map<String, Object> first = new HashMap<>();
        first.put("text", "first");
        parts.add(first);
        Map<String, Object> multimodal = new HashMap<>();
        multimodal.put("role", "user");
        multimodal.put("content", parts);
        assertEquals("user: first", ConversationMemoryText.of(fixed(multimodal)));

        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("role", "assistant");
        toolCall.put("content", null);
        toolCall.put("tool_calls", Collections.singletonList(Collections.singletonMap("id", "call_1")));
        assertTrue(ConversationMemoryText.of(fixed(toolCall)).contains("call_1"));

        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            longText.append('a');
        }
        assertEquals(ConversationMemoryText.MAX_CONTENT_CHARS,
                ConversationMemoryText.of(content("user", longText.toString())).length());
    }

    private static IContent content(String role, String body) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", role);
        message.put("content", body);
        return fixed(message);
    }

    private static IContent fixed(Map<String, Object> message) {
        return new IContent() {
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
                return Collections.emptyList();
            }
        };
    }
}