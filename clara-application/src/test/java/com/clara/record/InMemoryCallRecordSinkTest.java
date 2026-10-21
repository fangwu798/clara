package com.clara.record;

import com.clara.dto.record.CallRecord;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.usage.ProviderTokenUsage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link InMemoryCallRecordSink} 的平台累计用量测试：累计值按平台分组，且不随原始记录淘汰而丢失。
 */
public class InMemoryCallRecordSinkTest {

    /**
     * 同一平台的多次调用累加，不同平台分开统计；总量为各平台之和。
     */
    @Test
    public void aggregatesTokensByProviderAndTotal() {
        InMemoryCallRecordSink sink = new InMemoryCallRecordSink(10);

        sink.accept(record(ModelProviderEnum.DEEPSEEK, 10, 5, 15));
        sink.accept(record(ModelProviderEnum.DEEPSEEK, 20, 10, null));
        sink.accept(record(ModelProviderEnum.OPENAI, 100, 50, 150));

        ProviderTokenUsage deepseek = sink.usageByProvider().get(ModelProviderEnum.DEEPSEEK);
        assertEquals(2L, deepseek.getCalls());
        assertEquals(30L, deepseek.getInputTokens());
        assertEquals(15L, deepseek.getOutputTokens());
        assertEquals(45L, deepseek.getTotalTokens());

        ProviderTokenUsage openai = sink.usageByProvider().get(ModelProviderEnum.OPENAI);
        assertEquals(1L, openai.getCalls());
        assertEquals(150L, openai.getTotalTokens());

        assertEquals(3L, sink.totalUsage().getCalls());
        assertEquals(130L, sink.totalUsage().getInputTokens());
        assertEquals(65L, sink.totalUsage().getOutputTokens());
        assertEquals(195L, sink.totalUsage().getTotalTokens());
    }

    /**
     * 原始记录被容量淘汰后，累计用量仍保留全部调用。
     */
    @Test
    public void keepsAggregatesWhenRecordsAreEvicted() {
        InMemoryCallRecordSink sink = new InMemoryCallRecordSink(1);

        sink.accept(record(ModelProviderEnum.DEEPSEEK, 10, 5, 15));
        sink.accept(record(ModelProviderEnum.KIMI, 20, 10, 30));

        assertEquals(1, sink.snapshot().size());
        assertEquals(2L, sink.totalUsage().getCalls());
        assertEquals(45L, sink.totalUsage().getTotalTokens());
    }

    /**
     * 清空后记录与累计用量一并归零。
     */
    @Test
    public void clearResetsRecordsAndAggregates() {
        InMemoryCallRecordSink sink = new InMemoryCallRecordSink(10);
        sink.accept(record(ModelProviderEnum.DEEPSEEK, 10, 5, 15));

        sink.clear();

        assertEquals(0, sink.snapshot().size());
        assertEquals(0L, sink.totalUsage().getCalls());
        assertEquals(0L, sink.totalUsage().getTotalTokens());
        assertEquals(0, sink.usageByProvider().size());
    }

    private static CallRecord record(ModelProviderEnum provider, int input, int output, Integer total) {
        HttpResponse response = new HttpResponse();
        response.setInputTokens(input);
        response.setOutputTokens(output);
        response.setTotalTokens(total);
        return new CallRecord(HttpRequest.builder().build(), response, provider, "test-model");
    }
}