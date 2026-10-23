package com.clara.kernel.message.provider.response;

import com.clara.context.ConvContext;
import com.clara.dto.model.StreamEvent;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.config.ProviderCapabilityConfiguration;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.credential.ProviderCredentialConfiguration;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.http.TransportConfig;
import com.clara.kernel.message.provider.StreamAssembler;
import org.junit.Test;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link ResponseStreamAssembler} 的流式语义测试。
 *
 * <p>Responses 协议把正文放在顶层 {@code delta}、把工具调用放在终态事件的
 * {@code response.output}，两条路径差异很大。这里用真实解码器构造装配器，
 * 覆盖增量累积、终态裁剪与失败事件的错误提取。
 */
public class ResponseStreamAssemblerTest {

    private static ConvContext context() {
        ProviderCredentialConfiguration credential =
                new ProviderCredentialConfiguration("openai", null, "k".toCharArray());
        TransportConfig transport = new TransportConfig(
                Collections.singleton("https://api.openai.com"), 1000, 1000, 1 << 20, 1 << 20, false);
        Map<String, Object> parameters = new LinkedHashMap<>();
        ProviderCapabilityConfiguration text = new ProviderCapabilityConfiguration(
                URI.create("https://api.openai.com/v1/responses"), null, "gpt-5", "openai-responses",
                null, parameters, null);
        Map<CapabilityKindEnum, ProviderCapabilityConfiguration> capabilities = new LinkedHashMap<>();
        capabilities.put(CapabilityKindEnum.CHAT, text);
        return new ConvContext(null, "conv-1",
                new ProviderConfiguration(ModelProviderEnum.OPENAI, credential, transport, capabilities),
                CapabilityKindEnum.CHAT);
    }

    private static StreamAssembler<HttpResponse> assembler() {
        return new ResponseResponseDecoder().streamAssembler(HttpResponse.class);
    }

    @Test
    public void accumulatesTextAndReasoningDeltas() {
        try (ConvContext.Scope ignored = ConvContext.open(context())) {
            StreamAssembler<HttpResponse> assembler = assembler();

            List<StreamEvent> text = assembler.accept("response.output_text.delta",
                    "{\"delta\":\"Hello\"}");
            List<StreamEvent> more = assembler.accept("response.output_text.delta",
                    "{\"delta\":\" world\"}");
            List<StreamEvent> reasoning = assembler.accept("response.reasoning_text.delta",
                    "{\"delta\":\"why\"}");

            assertEquals(1, text.size());
            assertEquals(0L, text.get(0).getSequence());
            assertEquals("Hello", text.get(0).getPayload());
            assertEquals(" world", more.get(0).getPayload());
            assertEquals(1L, more.get(0).getSequence());
            assertEquals("why", reasoning.get(0).getPayload());
            assertEquals("Hello world", assembler.result().getContent());
            assertEquals("why", assembler.result().getReasoning());
            assertFalse(assembler.isTerminated());
        }
    }

    @Test
    public void completedEventCarriesToolCallsAndTerminates() {
        try (ConvContext.Scope ignored = ConvContext.open(context())) {
            StreamAssembler<HttpResponse> assembler = assembler();

            List<StreamEvent> events = assembler.accept("response.completed",
                    "{\"response\":{\"status\":\"completed\",\"output\":["
                            + "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"file_ops\","
                            + "\"arguments\":\"{\\\"op\\\":\\\"read\\\"}\"}]}}");

            assertTrue(assembler.isTerminated());
            assertNull(assembler.failure());
            assertEquals(1, events.size());
            assertTrue(events.get(0).getPayload().contains("c1"));
            assertEquals(1, assembler.result().getResponsesToolCalls().size());
            assertEquals("file_ops", assembler.result().getResponsesToolCalls().get(0).getName());
        }
    }

    @Test
    public void emptyToolCallListProducesNoEvent() {
        try (ConvContext.Scope ignored = ConvContext.open(context())) {
            StreamAssembler<HttpResponse> assembler = assembler();

            List<StreamEvent> events = assembler.accept("response.completed",
                    "{\"response\":{\"status\":\"completed\",\"output\":[]}}");

            assertTrue(events.isEmpty());
            assertTrue(assembler.isTerminated());
        }
    }

    @Test
    public void failedEventRecordsProviderFailure() {
        try (ConvContext.Scope ignored = ConvContext.open(context())) {
            StreamAssembler<HttpResponse> assembler = assembler();

            assembler.accept("response.failed",
                    "{\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\","
                            + "\"message\":\"upstream exploded\"}}}");

            assertTrue(assembler.isTerminated());
            assertNotNull(assembler.failure());
            assertTrue(assembler.failure().getMessage().contains("upstream exploded")
                    || assembler.failure().getError().getSafeMessage() != null);
        }
    }

    @Test
    public void failedEventWithoutErrorStillTerminates() {
        try (ConvContext.Scope ignored = ConvContext.open(context())) {
            StreamAssembler<HttpResponse> assembler = assembler();

            assembler.accept("response.failed", "{\"response\":{\"status\":\"failed\"}}");

            assertTrue(assembler.isTerminated());
            assertNotNull(assembler.failure());
        }
    }

    @Test
    public void acceptsEventsAfterTerminationAndBlankData() {
        try (ConvContext.Scope ignored = ConvContext.open(context())) {
            StreamAssembler<HttpResponse> assembler = assembler();
            assembler.accept("response.completed", "{\"response\":{\"status\":\"completed\"}}");

            assertTrue(assembler.accept("response.output_text.delta", "{\"delta\":\"x\"}").isEmpty());
            assertTrue(assembler.accept("response.output_text.delta", "  ").isEmpty());
            assertTrue(assembler.accept("response.output_text.delta", null).isEmpty());
        }
    }

    @Test
    public void ignoresMalformedPayloadAndStillHonoursTerminalEvent() {
        try (ConvContext.Scope ignored = ConvContext.open(context())) {
            StreamAssembler<HttpResponse> assembler = assembler();

            // 非对象 JSON 属于合法 JSON 但不含任何字段，直接忽略且不结束流。
            assertTrue(assembler.accept("response.output_text.delta", "[]").isEmpty());
            assertFalse(assembler.isTerminated());

            assertThrows(com.clara.exception.ClaraException.class,
                    () -> assembler.accept("response.completed", "not-json-object"));
            assertFalse(assembler.isTerminated());
        }
    }

    @Test
    public void terminalStatusIsWrittenIntoFinishReason() {
        try (ConvContext.Scope ignored = ConvContext.open(context())) {
            StreamAssembler<HttpResponse> assembler = assembler();

            assembler.accept("response.incomplete",
                    "{\"response\":{\"status\":\"incomplete\"}}");

            assertTrue(assembler.isTerminated());
            assertNotNull(assembler.result().getFinishReason());
        }
    }
}