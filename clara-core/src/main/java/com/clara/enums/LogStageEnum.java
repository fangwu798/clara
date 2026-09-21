package com.clara.enums;

/**
 * 请求日志阶段：一次模型往返中会发出日志的固定节点。
 *
 * <p>是什么、在哪一层：本枚举属于内核的共享枚举包（{@code com.clara.enums}），是配置层与实现层共用的
 * 词汇，不是模型 API 契约；它是 {@code RequestLogEntry.stage} 的取值类型，用来说明「这条日志是在往返的
 * 哪一步产生的」，接收方据此决定展示与归档方式，不必解析中文文案。
 *
 * <p>谁创建谁调用：由实现层的 HTTP 层（{@code com.clara.model.http} 的 {@code ModelInvoker} 与
 * {@code RequestLogEmitter}）在往返过程中创建并写入日志载荷；由日志观察者按
 * {@code RequestLogEntry.getStage()} 读取。调用方不要自行构造这些阶段。
 *
 * <p>使用方法（典型序列，同步往返）：
 * {@link #REQUEST_PREPARED} → {@link #REQUEST_SENT} → {@link #RESPONSE_RECEIVED} →
 * {@link #RESPONSE_DECODED}；流式往返在收响应后逐条追加 {@link #STREAM_EVENT}；任一步骤失败改发
 * {@link #CALL_FAILED}（或 {@link #CALL_CANCELLED}），且失败不再继续后续阶段。
 *
 * <p>生命周期与线程安全：枚举不可变、天然线程安全，可直接用 {@code ==} 比较，无需释放。
 *
 * <p>失败语义：作为枚举无失败语义；阶段本身不影响调用成败，日志是旁路——任何阶段发出的日志都不改变
 * 往返的控制流与业务结果。
 */
public enum LogStageEnum {
    /** 请求已准备：凭据已解析、请求头与请求体已组装，即将交给传输层发送。 */
    REQUEST_PREPARED,
    /** 已发送：传输层已把请求写出，携带发送耗时。 */
    REQUEST_SENT,
    /** 已收响应：已拿到状态码与响应头（含错误响应），携带往返耗时与响应字节数。 */
    RESPONSE_RECEIVED,
    /** SSE 事件：流式响应读取过程中收到的每一个事件（仅详细档产生）。 */
    STREAM_EVENT,
    /** 已解码：响应体已由 codec 解析为领域对象，携带解码耗时。 */
    RESPONSE_DECODED,
    /** 重试排队：本次往返已判定为可重试并安排了下次尝试，携带尝试次数。 */
    RETRY_SCHEDULED,
    /** 调用失败：往返以异常结束，携带错误码与中文文案；之后不再产生本次调用的其他阶段日志。 */
    CALL_FAILED,
    /** 调用取消：往返因协作式取消而中断，携带 traceId；之后不再产生本次调用的其他阶段日志。 */
    CALL_CANCELLED
}
