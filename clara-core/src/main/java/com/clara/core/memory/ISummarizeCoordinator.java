package com.clara.core.memory;

import com.clara.core.message.IContent;

import java.util.List;

/**
 * 压缩编排接口：历史超限时把需要淘汰的消息组总结为一条摘要。
 *
 * <p>core 定义的契约接口，由 clara-memory 负责编排；模型调用走 clara-model，总结提示词走
 * clara-skill 的提示词接口。压缩是一次性独立任务、不走 RAG 递归。
 */
public interface ISummarizeCoordinator {

    /**
     * 把需要淘汰的消息组总结为一条摘要内容。
     *
     * @param evicted 被淘汰的消息组，不可为 null
     * @return 摘要内容（system 指令形态），非 null
     */
    IContent summarize(List<IContent> evicted);
}
