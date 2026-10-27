package com.clara.record;

import com.clara.core.model.IResponse;
import com.clara.core.record.ICallRecordSink;
import com.clara.dto.record.CallRecord;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.http.HttpResponse;
import com.clara.usage.ProviderTokenUsage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ICallRecordSink} 的进程内默认实现：线程安全地保留最近 N 条调用记录，供上层读取。
 *
 * <p>是什么、在哪一层：application 层对 core 契约 {@link ICallRecordSink} 的实现，仅持有
 * {@link CallRecord} 对象引用，不打印、不落盘、不做任何序列化，因此不会泄露敏感信息。
 *
 * <p>容量语义：满容量后写入新记录时淘汰最旧的一条，只保留最近 {@code capacity} 条。
 *
 * <p>用量语义：除保留原始记录外，每次写入还会按平台累计调用次数与 token 用量。累计值不随原始记录淘汰，
 * 因此 {@code /usage} 看到的是本次进程内该平台的全部调用，而不是最近 N 条记录的局部和；
 * 同时按「平台/模型」累计一份明细，供按模型展示与趋势图使用。
 */
public final class InMemoryCallRecordSink implements ICallRecordSink {

    /**
     * 默认保留的最大记录条数。
     */
    public static final int DEFAULT_CAPACITY = 100;

    private final int capacity;
    private final Deque<CallRecord> records = new ArrayDeque<>();
    private final Map<ModelProviderEnum, ProviderTokenUsage> providerUsage = new EnumMap<>(ModelProviderEnum.class);
    private final Map<String, ProviderTokenUsage> modelUsage = new LinkedHashMap<>();
    private ProviderTokenUsage totalUsage = ProviderTokenUsage.EMPTY;

    /**
     * 使用默认容量（{@value #DEFAULT_CAPACITY}）创建接收方。
     */
    public InMemoryCallRecordSink() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 指定容量创建接收方。
     *
     * @param capacity 保留的最大记录条数，必须为正
     * @throws IllegalArgumentException capacity 非正时
     */
    public InMemoryCallRecordSink(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized void accept(CallRecord callRecord) {
        if (callRecord == null) {
            return;
        }
        if (records.size() >= capacity) {
            records.pollFirst();
        }
        records.addLast(callRecord);
        accumulate(callRecord);
    }

    /**
     * 返回当前保留记录的只读快照（按写入顺序，最旧在前）。
     *
     * @return 不可变列表，非 null
     */
    public synchronized List<CallRecord> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    /**
     * 按平台返回累计用量快照。
     *
     * @return 平台到用量快照的不可变映射，非 null；无调用时为空映射
     */
    public synchronized Map<ModelProviderEnum, ProviderTokenUsage> usageByProvider() {
        return Collections.unmodifiableMap(new EnumMap<>(providerUsage));
    }

    /**
     * 按「平台/模型」返回累计用量快照，键按字典序排序，保证报告与趋势图的配色顺序稳定。
     *
     * @return 模型标识到用量快照的不可变映射，非 null；无调用时为空映射
     */
    public synchronized Map<String, ProviderTokenUsage> usageByModel() {
        List<String> keys = new ArrayList<>(modelUsage.keySet());
        Collections.sort(keys);
        Map<String, ProviderTokenUsage> sorted = new LinkedHashMap<>();
        for (String key : keys) {
            sorted.put(key, modelUsage.get(key));
        }
        return Collections.unmodifiableMap(sorted);
    }

    /**
     * 返回全部平台的累计用量快照。
     *
     * @return 累计用量，非 null；无调用时各项均为 0
     */
    public synchronized ProviderTokenUsage totalUsage() {
        return totalUsage;
    }

    /**
     * 清空所有已保留的记录与累计用量。
     */
    public synchronized void clear() {
        records.clear();
        providerUsage.clear();
        modelUsage.clear();
        totalUsage = ProviderTokenUsage.EMPTY;
    }

    /**
     * 把一次调用累加到平台与总量台账。
     *
     * @param callRecord 调用记录，非 null
     */
    private void accumulate(CallRecord callRecord) {
        long input = 0L;
        long output = 0L;
        long total = 0L;
        IResponse response = callRecord.getResponse();
        if (response instanceof HttpResponse) {
            HttpResponse http = (HttpResponse) response;
            input = http.getInputTokens() == null ? 0L : http.getInputTokens().longValue();
            output = http.getOutputTokens() == null ? 0L : http.getOutputTokens().longValue();
            total = http.getTotalTokens() == null ? 0L : http.getTotalTokens().longValue();
            if (total <= 0L) {
                total = input + output;
            }
        }
        ProviderTokenUsage delta = new ProviderTokenUsage(1L, input, output, total);
        ProviderTokenUsage previous = providerUsage.get(callRecord.getProvider());
        providerUsage.put(callRecord.getProvider(), previous == null ? delta : previous.plus(delta));
        String modelKey = modelKey(callRecord);
        ProviderTokenUsage modelPrevious = modelUsage.get(modelKey);
        modelUsage.put(modelKey, modelPrevious == null ? delta : modelPrevious.plus(delta));
        totalUsage = totalUsage.plus(delta);
    }

    /**
     * 生成模型累计键：平台名 + 模型标识；模型标识缺失时用 {@code unknown} 占位。
     *
     * @param callRecord 调用记录，非 null
     * @return 形如 {@code DEEPSEEK/deepseek-v4-pro} 的键，非 null
     */
    private static String modelKey(CallRecord callRecord) {
        String model = callRecord.getModelType();
        String normalized = model == null || model.trim().isEmpty() ? "unknown" : model.trim();
        return callRecord.getProvider().name() + "/" + normalized;
    }
}
