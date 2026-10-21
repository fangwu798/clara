package com.clara.core.interaction;

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

    /** 显示当前工作目录的代码知识图谱状态（是否已导入、怎么导入、可视化地址）。 */
    void showCodeGraph();

    /** 把当前工作目录导入代码知识图谱（同步等待索引完成，可能几分钟）。 */
    void importCodeGraph();
}
