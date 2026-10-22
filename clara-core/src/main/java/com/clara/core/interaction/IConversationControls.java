package com.clara.core.interaction;

import com.clara.enums.interaction.HistoryClearScopeEnum;

/**
 * 会话级上下文控制：把「压缩上下文 / 新建对话 / 展示用量与代码图谱」这类需要访问装配层
 * 与当前会话标识的操作交给宿主实现，指令处理器只负责识别命令并回调。
 */
public interface IConversationControls {

    /** 压缩当前上下文（历史归纳为摘要）。 */
    void compressContext();

    /** 开始一段新对话（切换到新的会话标识，历史上下文清空）。 */
    void newConversation();

    /** 显示 token 用量统计（全部历史记录估算 + 本次进程累计调用）。 */
    void showUsage();

    /** 显示当前会话的历史聊天记录（仅用户与助手消息）。 */
    void showHistory();

    /**
     * 清理历史聊天记录，并同步当前会话的持久化上下文。
     *
     * @param scope 清理范围：全部 / 最早若干条 / 最新若干条，不可为 null
     * @param count 清理条数；{@link HistoryClearScopeEnum#ALL} 时忽略
     */
    void clearHistory(HistoryClearScopeEnum scope, int count);

    /** 显示当前工作目录的代码知识图谱状态（是否已导入、怎么导入、可视化地址）。 */
    void showCodeGraph();

    /** 把当前工作目录导入代码知识图谱（同步等待索引完成，可能几分钟）。 */
    void importCodeGraph();

    /**
     * 查看最近一轮（或指定 traceId）的执行链路。
     *
     * <p>默认空实现：不支持追踪的宿主无需改动；CLI 会覆盖为树形展示。
     *
     * @param selector 为空表示最近一轮；也可传 traceId 或 {@code list}
     */
    default void showTrace(String selector) {
        // 默认不提供链路回看。
    }
}
