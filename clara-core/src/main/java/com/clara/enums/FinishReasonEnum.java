package com.clara.enums;

/**
 * 结束原因：一次模型往返为什么停止生成。
 *
 * <p>是什么、在哪一层：内核共享枚举包（{@code com.clara.enums}）中的值类型，是响应对象
 * （ com.clara.kernel.http.HttpResponse#getFinishReason()）与流式终态事件共用的结束原因词汇。
 *
 * <p>取值来源：{@link #STOP} / {@link #LENGTH} / {@link #TOOL_CALLS} / {@link #CONTENT_FILTER} 由平台的
 * {@code finish_reason} 文本映射而来（见 {@code FinishReasonUtils}），{@link #ERROR} 兼作映射不出已知
 * 取值时的兜底，{@link #CANCELLED} 由本库在流被取消时写入终态事件的负载，不是平台返回值。
 *
 * <p>枚举不可变、天然线程安全，可直接用 {@code ==} 比较，无需释放。
 */
public enum FinishReasonEnum {
    /** 正常结束：模型给出了完整回答。 */
    STOP,
    /** 达到长度上限：输出被 max_tokens 等限制截断。 */
    LENGTH,
    /** 模型要求调用工具：需要执行工具并回传结果才能继续。 */
    TOOL_CALLS,
    /** 内容被平台的安全策略过滤。 */
    CONTENT_FILTER,
    /** 调用被取消：由本库在流式调用被取消时置入流终态事件的负载，非平台返回值。 */
    CANCELLED,
    /** 未知或缺失的结束原因：映射不出已知取值时的兜底。 */
    ERROR
}
