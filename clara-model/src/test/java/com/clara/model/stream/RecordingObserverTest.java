package com.clara.model.stream;

import com.clara.core.model.IResponse;
import com.clara.core.transport.RawHttpResponse;
import com.clara.dto.model.StreamEvent;
import com.clara.dto.tool.ToolCall;
import com.clara.enums.StreamEventEnum;
import com.clara.exception.ClaraError;
import com.clara.kernel.http.HttpResponse;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link RecordingObserver} 的行为测试：增量累积、工具参数分片拼装与终态语义。
 *
 * <p>观察者是流式调用的唯一汇聚点，这里不依赖真实网络，直接投递事件验证
 * 「拼装结果」与「失败断言」，避免把错误静默带进上层对话。
 */
public class RecordingObserverTest {

    private static StreamEvent event(StreamEventEnum type, long seq, String payload) {
        return new StreamEvent(type, seq, payload);
    }

    @Test
    public void rejectsNullResponseType() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingObserver<HttpResponse>(null, null, null));
    }

    @Test
    public void accumulatesTextReasoningAndToolArguments() {
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class, null, null);

        observer.onEvent(event(StreamEventEnum.TEXT_DELTA, 0L, "你"));
        observer.onEvent(event(StreamEventEnum.TEXT_DELTA, 1L, "好"));
        observer.onEvent(event(StreamEventEnum.REASONING_DELTA, 2L, "思考"));
        observer.onEvent(event(StreamEventEnum.TOOL_ARGUMENT_DELTA, 3L,
                "[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"file_ops\",\"arguments\":\"{\\\"op\\\":\"}}]"));

        assertEquals("你好", observer.text());
        assertEquals("思考", observer.reasoning());
        assertEquals(4, observer.eventCount());
        assertEquals(1, observer.toolArgumentFragments());
        List<ToolCall> calls = observer.toolCalls();
        assertEquals(1, calls.size());
        assertEquals("call-1", calls.get(0).getId());
        assertEquals("function", calls.get(0).getType());
        assertEquals("file_ops", calls.get(0).getFunction().getName());
        assertEquals("{\"op\":", calls.get(0).getFunction().getArguments());
    }

    @Test
    public void mergesFragmentsOfTheSameIndex() {
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class, null, null);
        observer.onEvent(event(StreamEventEnum.TOOL_ARGUMENT_DELTA, 0L,
                "[{\"index\":2,\"id\":\"c9\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"a\\\":\"}}]"));
        observer.onEvent(event(StreamEventEnum.TOOL_ARGUMENT_DELTA, 1L,
                "[{\"index\":2,\"function\":{\"arguments\":\"1}\"}}]"));

        List<ToolCall> calls = observer.toolCalls();
        assertEquals(1, calls.size());
        assertEquals("c9", calls.get(0).getId());
        assertEquals("{\"a\":1}", calls.get(0).getFunction().getArguments());
    }

    @Test
    public void ignoresMissingOrMalformedToolArguments() {
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class, null, null);

        observer.onEvent(event(StreamEventEnum.TOOL_ARGUMENT_DELTA, 0L, null));
        observer.onEvent(event(StreamEventEnum.TOOL_ARGUMENT_DELTA, 1L, "   "));
        observer.onEvent(event(StreamEventEnum.TOOL_ARGUMENT_DELTA, 2L, "not-json"));
        observer.onEvent(event(StreamEventEnum.TOOL_ARGUMENT_DELTA, 3L, "{\"index\":0}"));

        assertTrue(observer.toolCalls().isEmpty());
        // 无效分片仍然记数，方便排查「平台确实发了参数但解析失败」。
        assertEquals(4, observer.toolArgumentFragments());
    }

    @Test
    public void skipsToolCallWithoutNameAndArguments() {
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class, null, null);
        observer.onEvent(event(StreamEventEnum.TOOL_ARGUMENT_DELTA, 0L,
                "[{\"index\":0},{\"index\":1,\"id\":\"only-id\"}]"));

        assertTrue(observer.toolCalls().isEmpty());
    }

    @Test
    public void defaultsIndexAndEmptyArgumentsWhenMissing() {
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class, null, null);
        observer.onEvent(event(StreamEventEnum.TOOL_ARGUMENT_DELTA, 0L,
                "[{\"id\":\"c1\",\"function\":{\"name\":\"ping\"}}]"));

        List<ToolCall> calls = observer.toolCalls();
        assertEquals(1, calls.size());
        assertEquals("", calls.get(0).getFunction().getArguments());
    }

    @Test
    public void terminalEventCompletesWithTypedResponse() throws Exception {
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class, null, "tag");
        HttpResponse response = new HttpResponse();

        observer.onEvent(new StreamEvent(StreamEventEnum.RESPONSE_COMPLETE, 0L, "stop",
                null, null, response, null));

        assertTrue(observer.awaitTerminal(1L, TimeUnit.SECONDS));
        assertTrue(observer.isTerminated());
        assertEquals(response, observer.awaitResponse(1L, TimeUnit.SECONDS));
        assertEquals(response, observer.response());
        assertNull(observer.error());
    }

    @Test
    public void terminalEventWithUnexpectedTypeLeavesResponseUnset() throws Exception {
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class, null, null);
        observer.onEvent(new StreamEvent(StreamEventEnum.RESPONSE_COMPLETE, 0L, "stop",
                null, null, new ForeignResponse(), null));

        assertTrue(observer.isTerminated());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> observer.awaitResponse(1L, TimeUnit.SECONDS));
        assertTrue(error.getMessage().contains("no response of type"));
    }

    @Test
    public void errorEventFailsAwaitResponse() throws Exception {
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class, null, null);
        ClaraError failure = ClaraError.cancelled("trace-1");
        observer.onEvent(new StreamEvent(StreamEventEnum.ERROR, 0L, "boom", null, null, null, failure));

        assertTrue(observer.isTerminated());
        assertEquals(failure, observer.error());
        assertNull(observer.response());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> observer.awaitResponse(1L, TimeUnit.SECONDS));
        assertTrue(error.getMessage().contains("the stream failed"));
        assertTrue(error.getMessage().contains(failure.getCode()));
    }

    @Test
    public void errorEventWithoutErrorFallsBackToMissingResponse() {
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class, null, null);
        observer.onEvent(new StreamEvent(StreamEventEnum.ERROR, 0L, "boom", null, null, null, null));

        assertThrows(IllegalStateException.class, () -> observer.awaitResponse(1L, TimeUnit.SECONDS));
    }

    @Test
    public void awaitResponseTimesOut() {
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class, null, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> observer.awaitResponse(10L, TimeUnit.MILLISECONDS));
        assertTrue(error.getMessage().contains("timed out"));
        assertFalse(observer.isTerminated());
    }

    @Test
    public void printsEveryEventKindWithPrefix() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class,
                new PrintStream(buffer, true), "stream");
        HttpResponse response = new HttpResponse();

        observer.onEvent(event(StreamEventEnum.TEXT_DELTA, 0L, "hi"));
        observer.onEvent(new StreamEvent(StreamEventEnum.TOOL_CALL_COMPLETE, 1L, "{}", "c1", "file_ops",
                null, null));
        observer.onEvent(new StreamEvent(StreamEventEnum.RESPONSE_COMPLETE, 2L, "stop",
                null, null, response, null));
        observer.onEvent(new StreamEvent(StreamEventEnum.ERROR, 3L, "boom", null, null, null,
                ClaraError.cancelled("t")));

        String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(output.contains("[stream] TEXT_DELTA hi"));
        assertTrue(output.contains("TOOL_CALL_COMPLETE id=c1 name=file_ops"));
        assertTrue(output.contains("RESPONSE_COMPLETE"));
        assertTrue(output.contains("ERROR"));
    }

    @Test
    public void printsWithoutPrefixWhenTagIsNull() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        RecordingObserver<HttpResponse> observer = new RecordingObserver<>(HttpResponse.class,
                new PrintStream(buffer, true), null);

        observer.onEvent(event(StreamEventEnum.LOG, 0L, "plain"));

        String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(output.contains("LOG plain"));
        assertFalse(output.contains("[]"));
    }

    /** 非期望类型的响应，用来验证终态类型不匹配时的兜底。 */
    private static final class ForeignResponse implements IResponse {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends IResponse> Class<T> getCls() {
            return (Class<T>) ForeignResponse.class;
        }

        @Override
        public RawHttpResponse toResponse() {
            return null;
        }
    }
}