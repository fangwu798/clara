package com.clara.core.telemetry;

/**
 * 链路事件接收端：核心只负责产生 Span，怎么存、怎么展示由宿主决定。
 *
 * <p>实现约束：本方法在业务线程上同步调用，必须快速返回且不得抛出异常。落地实现应把写文件、
 * 网络导出等慢操作做成有界队列或异步批处理；观测失败不能影响本轮对话。
 */
public interface ITelemetrySink {

    /**
     * 接收一个已结束的 Span。
     *
     * @param event 不可变事件，非 null
     */
    void onSpan(TelemetryEvent event);
}
