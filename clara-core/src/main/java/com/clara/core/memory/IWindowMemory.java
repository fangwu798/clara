package com.clara.core.memory;

import com.clara.dto.memory.WindowPolicy;
import com.clara.core.message.IContent;

import java.util.List;

/**
 * 窗口记忆接口：以会话为单位管理短时历史消息，执行窗口限制与淘汰。
 *
 * <p>core 定义的契约接口，由 clara-memory 实现；淘汰以「完整工具往返」为一组，system 指令永居首位。
 */
public interface IWindowMemory {

    /**
     * 本窗口记忆绑定的会话标识。
     *
     * @return 会话标识，非 null
     */
    String conversationId();

    /**
     * 追加一批消息到历史末尾。
     *
     * @param messages 要追加的消息，不可为 null
     */
    void append(List<IContent> messages);

    /**
     * 读取当前历史消息（未裁剪的完整历史）。
     *
     * @return 历史消息的不可变快照，非 null
     */
    List<IContent> messages();

    /**
     * 按窗口策略裁剪并返回窗口内的历史消息（只读裁剪，不改变内部存储）。
     *
     * @param policy    窗口策略，不可为 null
     * @param estimator token 估算器，仅在按百分比限制时需要，可为 null
     * @return 裁剪后的窗口内历史，非 null
     */
    List<IContent> window(WindowPolicy policy, ITokenCountEstimator estimator);

    /**
     * 清空当前会话的历史消息。
     */
    void clear();
}
