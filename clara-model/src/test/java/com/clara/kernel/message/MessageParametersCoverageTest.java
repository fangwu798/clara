package com.clara.kernel.message;

import com.clara.dto.tool.ToolContext;
import com.clara.enums.RoleEnum;
import com.clara.enums.ThinkingEnum;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.Thinking;
import com.clara.kernel.message.delta.TokenUsage;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 请求 / 响应消息模型：对话与图片参数的全量读写、助手消息线格式与 token 用量校验。
 */
public class MessageParametersCoverageTest {

    /**
     * 图片请求继承链上的全部字段都可读写（Lombok 访问器全部执行）。
     */
    @Test
    public void exposesEveryImageAndChatParameter() {
        Thinking thinking = new Thinking();
        thinking.setType(ThinkingEnum.AUTO);
        List<ToolContext> tools = Collections.emptyList();
        HttpRequest request = HttpRequest.builder().build();

        request.setModel("model-1");
        request.setMessages(Collections.emptyList());
        request.setThinking(thinking);
        request.setTemperature(0.5d);
        request.setTopP(0.9d);
        request.setMaxTokens(100);
        request.setMaxCompletionTokens(200);
        request.setReasoningEffort("high");
        request.setFrequencyPenalty(0.1d);
        request.setPresencePenalty(0.2d);
        request.setResponseFormat("json_object");
        request.setStops(Arrays.asList("stop"));
        request.setToolChoice("auto");
        request.setParallelToolCalls(Boolean.TRUE);
        request.setLogprobs(Boolean.TRUE);
        request.setTopLogprobs(3);
        request.setServiceTier("default");
        request.setUserId("user-1");
        request.setStream(Boolean.TRUE);
        request.setIncludeUsage(Boolean.TRUE);
        request.setTools(tools);
        request.setPrompt("一只猫");
        request.setImage(Arrays.asList("data:image/png;base64,AAAA"));
        request.setSize("1024x1024");
        request.setSeed(7);
        request.setWatermark(Boolean.FALSE);
        request.setGuidanceScale(3.5d);
        request.setSequentialImageGeneration("auto");
        request.setMaxImages(2);
        request.setOutputFormat("png");
        request.setOptimizePromptMode("standard");
        request.setN(1);
        request.setQuality("high");
        request.setBackground("transparent");
        request.setModeration("low");
        request.setOutputCompression(80);
        request.setStyle("oil");

        assertEquals("model-1", request.getModel());
        assertEquals(ThinkingEnum.AUTO, request.getThinking().getType());
        assertEquals(0.5d, request.getTemperature(), 0.0001d);
        assertEquals(0.9d, request.getTopP(), 0.0001d);
        assertEquals(Integer.valueOf(100), request.getMaxTokens());
        assertEquals(Integer.valueOf(200), request.getMaxCompletionTokens());
        assertEquals("high", request.getReasoningEffort());
        assertEquals(0.1d, request.getFrequencyPenalty(), 0.0001d);
        assertEquals(0.2d, request.getPresencePenalty(), 0.0001d);
        assertEquals("json_object", request.getResponseFormat());
        assertEquals(Collections.singletonList("stop"), request.getStops());
        assertEquals("auto", request.getToolChoice());
        assertEquals(Boolean.TRUE, request.getParallelToolCalls());
        assertEquals(Boolean.TRUE, request.getLogprobs());
        assertEquals(Integer.valueOf(3), request.getTopLogprobs());
        assertEquals("default", request.getServiceTier());
        assertEquals("user-1", request.getUserId());
        assertEquals(Boolean.TRUE, request.getStream());
        assertEquals(Boolean.TRUE, request.getIncludeUsage());
        assertEquals(tools, request.getTools());
        assertEquals("一只猫", request.getPrompt());
        assertEquals(Collections.singletonList("data:image/png;base64,AAAA"), request.getImage());
        assertEquals("1024x1024", request.getSize());
        assertEquals(Integer.valueOf(7), request.getSeed());
        assertEquals(Boolean.FALSE, request.getWatermark());
        assertEquals(3.5d, request.getGuidanceScale(), 0.0001d);
        assertEquals("auto", request.getSequentialImageGeneration());
        assertEquals(Integer.valueOf(2), request.getMaxImages());
        assertEquals("png", request.getOutputFormat());
        assertEquals("standard", request.getOptimizePromptMode());
        assertEquals(Integer.valueOf(1), request.getN());
        assertEquals("high", request.getQuality());
        assertEquals("transparent", request.getBackground());
        assertEquals("low", request.getModeration());
        assertEquals(Integer.valueOf(80), request.getOutputCompression());
        assertEquals("oil", request.getStyle());
        assertEquals(HttpRequest.class, request.getCls());

        HttpRequest copy = HttpRequest.builder().build();
        assertEquals(request, copy);
        assertEquals(request.hashCode(), copy.hashCode());
        assertNotNull(request.toString());
        assertFalse(request.equals(new Object()));
    }

    /**
     * 助手消息的两种线格式（Completion 与 Responses）。
     */
    @Test
    public void encodesAssistantContent() {
        AssistantContent content = new AssistantContent("上一轮答案");
        assertEquals("assistant", content.getRole());
        assertEquals(RoleEnum.ASSISTANT, content.getRoleType());
        assertEquals("上一轮答案", content.toCompletionMessage().get("content"));
        assertEquals("assistant", content.toCompletionMessage().get("role"));
        List<java.util.Map<String, Object>> items = content.toResponseInputItems();
        assertEquals(1, items.size());
        assertEquals("message", items.get(0).get("type"));
        assertNotNull(new AssistantContent(null).toCompletionMessage());
    }

    /**
     * token 用量的合法构造与负数校验。
     */
    @Test
    public void validatesTokenUsage() {
        TokenUsage usage = new TokenUsage(10, 20, 30, 5);
        assertEquals(10, usage.getInputTokens());
        assertEquals(20, usage.getOutputTokens());
        assertEquals(Integer.valueOf(30), usage.getTotalTokens());
        assertEquals(Integer.valueOf(5), usage.getCacheTokens());
        assertEquals(20, new TokenUsage(10, 20, null, null).getOutputTokens());
        assertNegative(-1, 0, null, null);
        assertNegative(0, -1, null, null);
        assertNegative(0, 0, -1, null);
        assertNegative(0, 0, null, -1);
    }

    private static void assertNegative(int input, int output, Integer total, Integer cache) {
        try {
            new TokenUsage(input, output, total, cache);
            fail("负数 token 应抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("token"));
        }
    }
}