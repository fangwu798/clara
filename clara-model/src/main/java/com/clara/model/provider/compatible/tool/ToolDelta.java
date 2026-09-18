package com.clara.model.provider.compatible.tool;

import lombok.Data;

/**
 * 表示某个工具调用在一次流式增量中的变化，用于向上层推送参数增量事件。
 *
 * <p>用途：OpenAI 兼容流式响应会把一次工具调用的 id、函数名与参数拆成多个片段送达，
 * {@code OpenAiCompatibleStreamAccumulator} 每消费一块数据就产出一个本类实例（含本次新增的片段），
 * 由 {@code OpenAiCompatibleStreamingChatModel} 转换为 {@code TOOL_ARGUMENT_DELTA} 事件；
 * 流收尾时再按 {@link #getIndex()} 归并出的完整调用见 {@code chat.tool.ToolState}。
 *
 * <p>归属说明（重要）：本类是 OpenAI 兼容流式累加器的实现细节，单独放在 tool 包中只是为了与累加器
 * 主流程分开，<b>不是对外 API</b>，使用方不要直接依赖。
 *
 * <p>取值约定：{@link #getId()} 与 {@link #getName()} 只在本次增量携带该片段时才有值（可能整段为空串），
 * 其余情况为 null，便于上层区分「本次未出现」与「出现为受控空值」。
 */
@Data
public final class ToolDelta {
    /**
     * 工具调用在本次响应中的序号，用于把同一工具调用的增量归并到一起。
     */
    private final int index;
    /**
     * 工具调用的 id，增量中出现时才有值。
     */
    private final String id;
    /**
     * 工具调用的函数名，增量中出现时才有值。
     */
    private final String name;
    /**
     * 本次新增的参数片段，无新增时为 null。
     */
    private final String argumentsDelta;

    /**
     * 创建工具调用增量。
     *
     * @param index          工具调用序号
     * @param id             工具调用 id，可为 null
     * @param name           函数名，可为 null
     * @param argumentsDelta 参数片段，可为 null
     */
    public ToolDelta(int index, String id, String name, String argumentsDelta) {
        this.index = index;
        this.id = id;
        this.name = name;
        this.argumentsDelta = argumentsDelta;
    }
}
