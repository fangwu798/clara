package com.clara.model.provider.compatible.tool;

import lombok.Data;

/**
 * 表示单个工具调用在 OpenAI 兼容流式响应中的累积状态。
 *
 * <p>用途：SSE 分块会把一次工具调用的 id、函数名与参数拆成多个片段陆续送达，本类按序号保存这三段
 * 「已累积内容」，由 {@code OpenAiCompatibleStreamAccumulator} 在流收尾时拼成完整的
 * {@link com.clara.model.api.ToolCallContent}。
 *
 * <p>归属说明（重要）：本类是 OpenAI 兼容流式累加器的实现细节，单独放在 tool 包中只是为了与累加器
 * 主流程分开，<b>不是对外 API</b>，使用方不要直接依赖。
 *
 * <p>使用方式：{@link #getArguments()} 等访问器直接返回可变的累积缓冲，调用方按需追加片段并自行
 * 统计长度（累计字符数上限由累加器统一控制），读取时用 {@code toString()} 取当前内容。
 */
@Data
public final class ToolState {
    /**
     * 已累积的工具调用 id，片段按到达顺序拼接。
     */
    private final StringBuilder id = new StringBuilder();
    /**
     * 已累积的函数名，片段按到达顺序拼接。
     */
    private final StringBuilder name = new StringBuilder();
    /**
     * 已累积的参数片段，拼起来是完整的 JSON 字符串。
     */
    private final StringBuilder arguments = new StringBuilder();
}
