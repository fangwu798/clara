package com.clara.interaction;

import com.clara.core.interaction.IUserInterventionSink;
import com.clara.core.interaction.IUserInterventionSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 执行期补充信息缓冲区：控制台监听线程写入，编排线程消费。
 *
 * <p>线程安全由 {@link ConcurrentLinkedQueue} 保证；{@link #drain()} 与 {@link #offer(String)}
 * 可以并发执行，单条补充不会重复消费。文本提交时会去除首尾空白，纯空白内容直接忽略。
 */
public final class UserInterventionBuffer implements IUserInterventionSink, IUserInterventionSource {

    private final Queue<String> pending = new ConcurrentLinkedQueue<>();

    @Override
    public void offer(String text) {
        if (text == null) {
            return;
        }
        String trimmed = text.trim();
        if (!trimmed.isEmpty()) {
            pending.offer(trimmed);
        }
    }

    @Override
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    @Override
    public List<String> drain() {
        List<String> drained = new ArrayList<String>();
        String item;
        while ((item = pending.poll()) != null) {
            drained.add(item);
        }
        return drained;
    }
}
