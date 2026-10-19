package com.clara.orchestration.record;

import com.clara.core.record.ICallRecordSink;
import com.clara.dto.record.CallRecord;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link CallRecordPublisher} 单元测试：未配置接收方 / 入参缺失时静默跳过，正常时发布一条记录。
 */
public class CallRecordPublisherTest {

    @Test
    public void publishDoesNothingWhenSinkIsNull() {
        CallRecordPublisher publisher = new CallRecordPublisher(null);
        publisher.publish(request(), response(), ModelProviderEnum.OPENAI, "m1");
        assertNotNull(publisher);
    }

    @Test
    public void publishSkipsWhenRequestOrResponseOrProviderMissing() {
        RecordingSink sink = new RecordingSink();
        CallRecordPublisher publisher = new CallRecordPublisher(sink);

        publisher.publish(null, response(), ModelProviderEnum.OPENAI, "m1");
        publisher.publish(request(), null, ModelProviderEnum.OPENAI, "m1");
        publisher.publish(request(), response(), null, "m1");

        assertTrue(sink.records.isEmpty());
    }

    @Test
    public void publishForwardsSingleRecordWithSameInstances() {
        RecordingSink sink = new RecordingSink();
        CallRecordPublisher publisher = new CallRecordPublisher(sink);

        HttpRequest request = request();
        HttpResponse response = response();
        publisher.publish(request, response, ModelProviderEnum.DEEPSEEK, "deepseek-chat");

        assertEquals(1, sink.records.size());
        CallRecord record = sink.records.get(0);
        assertSame(request, record.getRequest());
        assertSame(response, record.getResponse());
        assertEquals(ModelProviderEnum.DEEPSEEK, record.getProvider());
        assertEquals("deepseek-chat", record.getModelType());
    }

    // ---- helpers -----------------------------------------------------------

    private static HttpRequest request() {
        return HttpRequest.builder().model("m1").build();
    }

    private static HttpResponse response() {
        return new HttpResponse();
    }

    private static final class RecordingSink implements ICallRecordSink {

        private final List<CallRecord> records = new ArrayList<>();

        @Override
        public void accept(CallRecord record) {
            records.add(record);
        }
    }
}
