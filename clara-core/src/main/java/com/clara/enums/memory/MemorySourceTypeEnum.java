package com.clara.enums.memory;

/**
 * 语义记忆的来源类型。
 *
 * <p>业务约定语义来源固定为三种：提示词、工具描述、对话记录（业务设计 MR9 / MD3）。
 */
public enum MemorySourceTypeEnum {
    /** 提示词描述（来自 clara-skill）。 */
    PROMPT,
    /** 工具方法描述（来自 clara-skill）。 */
    TOOL_DESCRIPTION,
    /** 对话记录（被淘汰的历史消息向量化沉淀）。 */
    CONVERSATION
}
