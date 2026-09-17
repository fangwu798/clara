package com.clara.model.provider.http.event;

/**
 * 表示 SSE 单个事件在解析过程中的可变状态，从 {@link ProviderSseParser} 中独立出来。
 *
 * <p>用途：在逐行解析响应流时累积当前事件的 {@code data}/{@code event}/{@code id}/{@code retry}
 * 字段，以及事件体积与已派发数量的计数；事件派发后通过 {@link #resetEvent()} 复用同一实例，
 * 避免为每个事件创建对象。
 *
 * <p>注意：本类只做原样保存，不校验字段值，也不判断体积与数量上限，相关判定由解析器负责。
 * 非线程安全，仅供单次解析流程在单个线程内使用。
 */
final class SseState {
    /**
     * 已累积的多行 data 内容。
     */
    final StringBuilder data = new StringBuilder();
    /**
     * 当前事件的 event 字段值，未声明时为 null。
     */
    String event;
    /**
     * 当前事件的 id 字段值，未声明时为 null。
     */
    String id;
    /**
     * 当前事件的 retry 字段值，未声明或非法时为 null。
     */
    Long retryMillis;
    /**
     * 当前事件已累计的字节数，用于校验单事件上限。
     */
    int eventBytes;
    /**
     * 已派发的事件数量。
     */
    int events;
    /**
     * 当前事件是否已解析出至少一个字段，用于统计字段间换行符体积。
     */
    boolean hasField;
    /**
     * 当前事件是否包含 data 字段。
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
