package com.clara.orchestration.context;

import com.clara.core.memory.ISummarizeCoordinator;
import com.clara.core.message.IContent;

import java.util.List;

/**
 * 窗口压缩协调器：历史命中窗口上限时，把被淘汰的消息组交给 {@link ISummarizeCoordinator} 压缩为摘要。
 *
 * <p>编排层只负责「何时压缩、压缩结果如何回填」；具体压缩（模型调用与提示词）由 clara-memory 的
 * {@code ISummarizeCoordinator} 实现。摘要请求本身不进入历史、不再次触发压缩、不产生调用记录，避免递归。
 */
public final class WindowCompressionCoordinator {

    private final ISummarizeCoordinator summarizeCoordinator;

    /**
     * 创建窗口压缩协调器。
     *
     * @param summarizeCoordinator 压缩编排实现；为 null 时压缩被跳过
     */
    public WindowCompressionCoordinator(ISummarizeCoordinator summarizeCoordinator) {
        this.summarizeCoordinator = summarizeCoordinator;
    }

    /**
     * 把被淘汰的消息组压缩为一条摘要。
     *
     * @param evicted 被淘汰的消息组，可为 null 或空
     * @return 摘要内容（system 指令形态）；未配置压缩实现或无淘汰时为 null
     */
    public IContent summarize(List<IContent> evicted) {
        if (summarizeCoordinator == null || evicted == null || evicted.isEmpty()) {
            return null;
        }
        return summarizeCoordinator.summarize(evicted);
    }
}
