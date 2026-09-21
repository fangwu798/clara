package com.clara.kernel.http.execute.sse;

/**
 * 表示 SSE 单个事件在解析过程中的可变状态，从 {@link SseParser} 中独立出来。
 *
 * <p>是什么与所属层：本类属于 {@code clara-model} 的公共 HTTP 层（{@code com.clara.model.http.sse}），
 * 是包私有（package-private）的实现细节，仅供同包的 {@link SseParser} 使用；它承载逐行解析时当前事件的
 * {@code data}/{@code event}/{@code id}/{@code retry} 字段，以及事件体积与已派发数量的计数。
 *
 * <p>谁创建谁调用：只由 {@link SseParser#parse} 在每次解析开始时创建，解析过程中由解析器自身读写；包外
 * 无法访问，也不需要访问。
 *
 * <p>使用方法：解析器创建实例 → 每读到一行就累积字段 → 遇到空行调用 {@link #resetEvent()} 前把字段读出
 * 并派发事件 → 继续复用同一实例。事件派发后通过 {@link #resetEvent()} 复用同一实例，避免为每个事件创建
 * 对象。没有 close 或释放动作，解析结束即随方法栈一起回收。
 *
 * <p>生命周期与线程安全：非线程安全，仅供单次解析流程在单个线程内使用；一次 {@code parse} 调用对应一个
 * 独立实例。
 *
 * <p>失败语义：本类只做原样保存，不校验字段值，也不判断体积与数量上限，相关判定由解析器负责，因此本类
 * 的方法不会抛异常。
 */
final class SseState {
    /**
     * 已累积的多行 data 内容，初始为空，多行之间由解析器插入换行符。
     */
    final StringBuilder data = new StringBuilder();
    /**
     * 当前事件的 event 字段值，未声明时为 null；默认值 null。
     */
    String event;
    /**
     * 当前事件的 id 字段值，未声明时为 null；默认值 null。
     */
    String id;
    /**
     * 当前事件的 retry 字段值，单位毫秒，未声明或非法时为 null；默认值 null。
     */
    Long retryMillis;
    /**
     * 当前事件已累计的字节数，用于校验单事件上限；初始为 0。
     */
    int eventBytes;
    /**
     * 已派发的事件数量，跨事件保留，不在 {@link #resetEvent()} 中清零；初始为 0。
     */
    int events;
    /**
     * 当前事件是否已解析出至少一个字段，用于统计字段间换行符体积；初始为 false。
     */
    boolean hasField;
    /**
     * 当前事件是否包含 data 字段；初始为 false。
     */
    boolean hasData;

    /**
     * 重置当前事件的字段与体积统计，保留已派发事件数量。
     */
    void resetEvent() {
        data.setLength(0);
        event = null;
        id = null;
        retryMillis = null;
        eventBytes = 0;
        hasField = false;
        hasData = false;
    }
}
