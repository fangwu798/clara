package com.clara.telemetry;

import com.clara.core.telemetry.ITelemetrySink;
import com.clara.core.telemetry.TelemetryEvent;
import com.clara.core.telemetry.TelemetryStatusEnum;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链路采样器：成功请求按比例保留，错误、取消与慢请求 100% 保留。
 *
 * <p>为什么必须缓冲整条 trace：采样决策依赖根 Span 的终态与总耗时，而 Span 是子节点先结束、根节点最后结束。
 * 若在第一条 Span 到达时就按 traceId 哈希决定，错误与慢请求会被提前丢掉。这里按 traceId 暂存到根 Span
 * 关闭，再一次性放行或丢弃。
 *
 * <p>内存边界：最多暂存 {@value #MAX_BUFFERED_TRACES} 条未完成 trace，单条最多
 * {@value #MAX_EVENTS_PER_TRACE} 个 Span；超限时按到达顺序先放行最早的一条，避免异常链路导致内存无界增长。
 *
 * <p>指标不经本类采样：{@link TelemetryMetrics} 直接挂在组合接收端上，token、错误率等计数始终完整。
 */
public final class SamplingTelemetrySink implements ITelemetrySink, Closeable {

    /** 默认最多暂存的未完成 trace 数。 */
    private static final int MAX_BUFFERED_TRACES = 256;

    /** 单条 trace 最多暂存的 Span 数。 */
    private static final int MAX_EVENTS_PER_TRACE = 1024;

    /** 成功链路的保留比例，0~1。 */
    private final double sampleRate;

    /** 慢请求阈值（毫秒）；小于等于 0 表示不按耗时保留。 */
    private final long slowTraceMillis;

    /** 下游接收端。 */
    private final List<ITelemetrySink> delegates;

    /** 未完成 trace 的缓冲区，按首次出现顺序排列。 */
    private final Map<String, List<TelemetryEvent>> buffers = new LinkedHashMap<>();

    /**
     * 创建采样器。
     *
     * @param sampleRate     成功链路保留比例，超出 0~1 时截断
     * @param slowTraceMillis 慢请求阈值毫秒，小于等于 0 表示关闭
     * @param delegates      下游接收端，可为 null
     */
    public SamplingTelemetrySink(double sampleRate, long slowTraceMillis, List<ITelemetrySink> delegates) {
        this.sampleRate = Math.max(0.0d, Math.min(1.0d, sampleRate));
        this.slowTraceMillis = slowTraceMillis;
        List<ITelemetrySink> copy = new ArrayList<>();
        if (delegates != null) {
            for (ITelemetrySink delegate : delegates) {
                if (delegate != null) {
                    copy.add(delegate);
                }
            }
        }
        this.delegates = Collections.unmodifiableList(copy);
    }

    @Override
    public void onSpan(TelemetryEvent event) {
        if (event == null) {
            return;
        }
        if (sampleRate >= 1.0d) {
            emit(Collections.singletonList(event));
            return;
        }
        List<TelemetryEvent> ready = null;
        synchronized (buffers) {
            List<TelemetryEvent> buffer = buffers.get(event.getTraceId());
            if (buffer == null) {
                buffer = new ArrayList<>();
                buffers.put(event.getTraceId(), buffer);
            }
            buffer.add(event);
            if (event.getParentSpanId() == null) {
                ready = buffers.remove(event.getTraceId());
            } else if (buffer.size() >= MAX_EVENTS_PER_TRACE) {
                ready = buffers.remove(event.getTraceId());
            } else if (buffers.size() > MAX_BUFFERED_TRACES) {
                ready = removeOldest();
            }
        }
        if (ready != null) {
            emitIfKept(ready);
        }
    }

    /**
     * 放行所有仍在缓冲的 trace；关闭前调用，避免进程退出时丢掉最后一条链路。
     */
    @Override
    public void close() {
        List<List<TelemetryEvent>> pending = new ArrayList<>();
        synchronized (buffers) {
            pending.addAll(buffers.values());
            buffers.clear();
        }
        for (List<TelemetryEvent> events : pending) {
            emitIfKept(events);
        }
    }

    private List<TelemetryEvent> removeOldest() {
        java.util.Iterator<Map.Entry<String, List<TelemetryEvent>>> iterator = buffers.entrySet().iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        Map.Entry<String, List<TelemetryEvent>> oldest = iterator.next();
        iterator.remove();
        return oldest.getValue();
    }

    private void emitIfKept(List<TelemetryEvent> events) {
        if (events == null || events.isEmpty() || !keep(events)) {
            return;
        }
        emit(events);
    }

    private boolean keep(List<TelemetryEvent> events) {
        if (events.isEmpty()) {
            return false;
        }
        long firstStart = Long.MAX_VALUE;
        long lastEnd = 0L;
        boolean hasRoot = false;
        for (TelemetryEvent event : events) {
            if (event.getStatus() == TelemetryStatusEnum.ERROR
                    || event.getStatus() == TelemetryStatusEnum.CANCELLED) {
                return true;
            }
            if (event.getParentSpanId() == null) {
                hasRoot = true;
            }
            firstStart = Math.min(firstStart, event.getStartEpochMillis());
            lastEnd = Math.max(lastEnd, event.getStartEpochMillis() + event.getDurationMillis());
        }
        long duration = firstStart == Long.MAX_VALUE ? 0L : Math.max(0L, lastEnd - firstStart);
        if (slowTraceMillis > 0L && duration >= slowTraceMillis) {
            return true;
        }
        if (sampleRate <= 0.0d) {
            return false;
        }
        // 没有根 Span 的残缺链路（进程崩溃 / 接收端异常）不参与成功采样，避免把半条链路写进存储。
        if (!hasRoot) {
            return false;
        }
        int bucket = Math.floorMod(events.get(0).getTraceId().hashCode(), 10_000);
        return bucket < Math.round(sampleRate * 10_000.0d);
    }

    private void emit(List<TelemetryEvent> events) {
        for (ITelemetrySink delegate : delegates) {
            for (TelemetryEvent event : events) {
                try {
                    delegate.onSpan(event);
                } catch (RuntimeException ignored) {
                    // 分析 / 导出是旁路：任一接收端失败不能影响其它接收端。
                }
            }
        }
    }
}