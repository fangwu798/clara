package com.clara.record;

import com.clara.dto.record.CallRecord;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.usage.ProviderTokenUsage;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

        Map<String, ProviderTokenUsage> byModel = sink.usageByModel();
        assertEquals(2, byModel.size());
        assertEquals(2L, byModel.get("DEEPSEEK/test-model").getCalls());
        assertEquals(45L, byModel.get("DEEPSEEK/test-model").getTotalTokens());
        assertEquals(1L, byModel.get("OPENAI/test-model").getCalls());
        assertEquals(150L, byModel.get("OPENAI/test-model").getTotalTokens());
    }

    /**
     * 同一平台的不同模型分开累计，模型键带平台前缀避免跨平台重名。
     */
    @Test
    public void aggregatesTokensByModel() {
        InMemoryCallRecordSink sink = new InMemoryCallRecordSink(10);

        sink.accept(record(ModelProviderEnum.DEEPSEEK, "deepseek-chat", 10, 5, 15));
        sink.accept(record(ModelProviderEnum.DEEPSEEK, "deepseek-chat", 20, 10, 30));
        sink.accept(record(ModelProviderEnum.DEEPSEEK, "deepseek-reasoner", 7, 3, 10));
        sink.accept(record(ModelProviderEnum.DEEPSEEK, null, 1, 1, 2));

        Map<String, ProviderTokenUsage> byModel = sink.usageByModel();
        assertEquals(3, byModel.size());
        assertEquals(2L, byModel.get("DEEPSEEK/deepseek-chat").getCalls());
        assertEquals(45L, byModel.get("DEEPSEEK/deepseek-chat").getTotalTokens());
        assertEquals(1L, byModel.get("DEEPSEEK/deepseek-reasoner").getCalls());
        assertEquals(10L, byModel.get("DEEPSEEK/deepseek-reasoner").getTotalTokens());
        assertTrue(byModel.containsKey("DEEPSEEK/unknown"));
        assertEquals(2L, byModel.get("DEEPSEEK/unknown").getTotalTokens());
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
        assertEquals(0, sink.usageByModel().size());
    }

    private static CallRecord record(ModelProviderEnum provider, int input, int output, Integer total) {
        return record(provider, "test-model", input, output, total);
    }

    private static CallRecord record(ModelProviderEnum provider, String model, int input, int output, Integer total) {
        HttpResponse response = new HttpResponse();
        response.setInputTokens(input);
        response.setOutputTokens(output);
        response.setTotalTokens(total);
        return new CallRecord(HttpRequest.builder().build(), response, provider, model);
    }
}
