package com.clara.model.provider.http.event;

import lombok.Data;

/**
 * 表示服务提供商 SSE 事件，对应 SSE 协议中的一个完整事件块。
 *
 * <p>用途：承载解析后的原始事件字段，作为 {@link ProviderSseParser} 的输出与
 * {@code ProviderSseListener} 的输入，供上层适配器按事件名与数据完成协议解码。
 *
 * <p>注意：本类只做数据承载，不对内容做任何校验，字段均可能为 null。其中 {@code event} 由解析器
 * 保证非空（服务端未声明时使用默认事件名），{@code retryMillis} 仅在服务端声明了合法的数字
 * retry 时才非空。
 */
@Data
public final class ProviderSseEvent {
    /**
     * 事件名，对应 SSE 的 event 字段；服务端未声明时为默认事件名。
     *
     * <p>用途与厂商差异：event 由 SSE 规范定义、可选，通过它区分同一流中不同类型的事件。
     * OpenAI 兼容系（OpenAI、DeepSeek、Azure OpenAI、火山引擎、通义等）只发 {@code data:} 行，
     * 该字段恒为默认值 {@code message}；Anthropic Messages 会声明 {@code message_start}、
     * {@code content_block_delta}、{@code message_stop} 等，OpenAI Responses API 会声明
     * {@code response.output_text.delta} 等。适配器需按各自厂商约定使用：OpenAI 兼容系要求必须是
     * 默认事件名，Anthropic 则要求事件名与 {@code data} 中的 {@code type} 一致后再分派。
     */
    private final String event;
    /**
     * 事件数据，对应 SSE 的 data 字段；多行 data 已按规范用换行符拼接。
     */
    private final String data;
    /**
     * 事件标识，对应 SSE 的 id 字段；服务端未声明时为 null。
     *
     * <p>用途与格式：SSE 规范用 id 给事件编号，客户端断线重连时会把最后收到的值作为
     * {@code Last-Event-ID} 请求头回传，服务端据此从断点续推，也可用于事件去重与排序。
     * 取值为不含 NUL 的任意字符串，例如 {@code id: 42}、{@code id: evt-000123}；含 NUL 时按规范忽略。
     * 大模型厂商的流式响应基本不发送该字段（OpenAI 兼容系只用 data 与 {@code [DONE]}，
     * Anthropic 只用 event 与 data）。
     */
    private final String id;
    /**
     * 重连间隔毫秒数，对应 SSE 的 retry 字段；未声明或非法时为 null。
     *
     * <p>用途与格式：服务端用它声明断线后客户端应等待多少毫秒再重连，例如 {@code retry: 3000}；
     * 值必须是纯数字，含符号、空格或其它字符时按规范忽略。浏览器 EventSource 会据此自动重连，
     * Java 客户端不自动重连，因此该字段目前仅透传给上层，不产生实际行为。
     */
    private final Long retryMillis;

    /**
     * 创建 SSE 事件。
     *
     * @param event       事件名
     * @param data        事件数据
     * @param id          事件标识，可为 null
     * @param retryMillis 重连间隔毫秒数，可为 null
     */
    public ProviderSseEvent(String event, String data, String id, Long retryMillis) {
        this.event = event;
        this.data = data;
        this.id = id;
        this.retryMillis = retryMillis;
    }
}
