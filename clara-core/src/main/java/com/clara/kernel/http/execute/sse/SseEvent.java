package com.clara.kernel.http.execute.sse;


import com.clara.kernel.api.IRequest;
import com.clara.kernel.api.execute.IHttpExecute;
import com.clara.kernel.api.execute.ISseListener;
import lombok.Data;

/**
 * SSE 事件：表示服务提供商流式响应中的一个完整 SSE 事件块。
 *
 * <p>是什么与所属层：本类属于 {@code clara-core} 的 HTTP 层（{@code com.clara.kernel.http.execute.sse}），
 * 是实现层值对象，不是模型 API 契约；它承载解析后的原始事件字段，作为 {@link SseParser} 的输出与
 * {@link ISseListener} 的输入，供上层适配器按事件名与数据完成协议解码。
 *
 * <p>谁创建谁调用：只由 {@link SseParser} 在解析过程中创建；由传输实现（{@code Jdk8HttpTransport}）转交给
 * {@link ISseListener} 的实现使用。适配器不自行构造本类的实例（测试除外）。
 *
 * <p>使用方法：实现 {@link ISseListener} 并在
 * {@link IHttpExecute#execute(IRequest request, ISseListener listener) 流式调用}中接收本对象，用 {@code getEvent()} 分派事件类型、
 * 用 {@code getData()} 解码内容；解析完成后交给流累加器处理。本类没有 close 或释放动作，也无需归还给
 * 任何池，用完即可被回收。
 *
 * <p>生命周期与线程安全：实例不可变（字段均为 final 且无 setter），一次事件一个实例，只在创建它的解析
 * 线程内回调传递，不跨线程共享；解析器在派发后即复用内部状态，因此回调结束后本实例仍然独立有效。
 *
 * <p>失败语义：本类不做任何校验，构造与读取都不会抛异常；字段非法（例如适配器预期的 JSON 无法解析）由
 * 监听器实现自行判定并按协议错误抛出。字段均允许为 null，消费前必须按各自的空值语义处理。
 *
 * <p>注意：其中 {@code event} 由解析器保证非空（服务端未声明时使用默认事件名），{@code retryMillis}
 * 仅在服务端声明了合法的数字 retry 时才非空。
 */
@Data
public final class SseEvent {
    /**
     * 事件名，对应 SSE 的 event 字段；服务端未声明时为默认事件名。
     *
     * <p>用途与厂商差异：event 由 SSE 规范定义、可选，通过它区分同一流中不同类型的事件。
     * OpenAI 兼容系（OpenAI、DeepSeek、Azure OpenAI、火山引擎、通义等）只发 {@code data:} 行，
     * 该字段恒为默认值 {@code message}；Anthropic Messages 会声明 {@code message_start}、
     * {@code content_block_delta}、{@code message_stop} 等，OpenAI Responses API 会声明
     * {@code response.output_text.delta} 等。适配器需按各自厂商约定使用：OpenAI 兼容系要求必须是
     * 默认事件名，Anthropic 则要求事件名与 {@code data} 中的 {@code type} 一致后再分派。
     *
     * <p>取值：非空字符串，默认值 {@code message}；不可为 null。
     */
    private final String event;
    /**
     * 事件数据，对应 SSE 的 data 字段；多行 data 已按规范用换行符拼接。
     *
     * <p>取值：字符串，可为 null（无 data 的事件块按规范不会派发，因此实际消费到的实例通常非 null，
     * 但本类不做保证）；不存在默认值。
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
     *
     * <p>取值：字符串，可为 null；默认值 null（未声明）。
     */
    private final String id;
    /**
     * 重连间隔毫秒数，对应 SSE 的 retry 字段；未声明或非法时为 null。
     *
     * <p>用途与格式：服务端用它声明断线后客户端应等待多少毫秒再重连，例如 {@code retry: 3000}；
     * 值必须是纯数字，含符号、空格或其它字符时按规范忽略。浏览器 EventSource 会据此自动重连，
     * Java 客户端不自动重连，因此该字段目前仅透传给上层，不产生实际行为。
     *
     * <p>取值：非负毫秒数，单位毫秒，可为 null；默认值 null（未声明或其值非法）。
     */
    private final Long retryMillis;

    /**
     * 创建 SSE 事件。
     *
     * @param event       事件名，非 null；为空时上层会使用默认事件名 {@code message}
     * @param data        事件数据，可为 null
     * @param id          事件标识，可为 null
     * @param retryMillis 重连间隔毫秒数，可为 null
     */
    public SseEvent(String event, String data, String id, Long retryMillis) {
        this.event = event;
        this.data = data;
        this.id = id;
        this.retryMillis = retryMillis;
    }
}
