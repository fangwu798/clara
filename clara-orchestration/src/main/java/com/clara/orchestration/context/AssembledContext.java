package com.clara.orchestration.context;

import com.clara.core.message.IContent;
import com.clara.dto.tool.ToolContext;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上下文装配结果：本轮请求消息（system 指令居首）与可用工具声明。
 *
 * <p>实例不可变：构造时对消息和工具列表做防御性拷贝，并通过只读视图对外暴露。
 */
@Data
public final class AssembledContext {

    /** 本轮请求消息，system 指令位于首位。 */
    private final List<IContent> messages;

    /** 本轮可用工具声明。 */
    private final List<ToolContext> tools;

    /**
     * 创建装配结果。
     *
     * @param messages 本轮请求消息
     * @param tools    本轮可用工具声明
     */
    AssembledContext(List<IContent> messages, List<ToolContext> tools) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
    }
}
