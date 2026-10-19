package com.clara.record;

import com.clara.core.record.ICallRecordSink;
import com.clara.dto.record.CallRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * {@link ICallRecordSink} 的进程内默认实现：线程安全地保留最近 N 条调用记录，供上层读取。
 *
 * <p>是什么、在哪一层：application 层对 core 契约 {@link ICallRecordSink} 的实现，仅持有
 * {@link CallRecord} 对象引用，不打印、不落盘、不做任何序列化，因此不会泄露敏感信息。
 *
 * <p>容量语义：满容量后写入新记录时淘汰最旧的一条，只保留最近 {@code capacity} 条。
 */
public final class InMemoryCallRecordSink implements ICallRecordSink {

    /**
     * 默认保留的最大记录条数。
     */
    public static final int DEFAULT_CAPACITY = 100;

    private final int capacity;
    private final Deque<CallRecord> records = new ArrayDeque<>();

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
     * 清空所有已保留的记录。
     */
    public synchronized void clear() {
        records.clear();
    }
}
