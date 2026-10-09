package com.clara.orchestration.context;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话知识找回协调器：用当前输入向量按会话检索被淘汰的历史知识，命中的知识作为补充上下文注入。
 *
 * <p>检索能力由 {@link ISemanticMemoryStore} 提供、向量化由 {@link IEmbeddingClient} 提供；编排层只负责
 * 「何时检索、命中结果如何注入」。检索条数与相似度阈值来自 control 配置。
 */
public final class SemanticRecallCoordinator {

    private final IEmbeddingClient embeddingClient;
    private final ISemanticMemoryStore semanticMemoryStore;

    /**
     * 创建知识找回协调器。
     *
     * @param embeddingClient    向量化客户端，可为 null（此时不检索）
     * @param semanticMemoryStore 语义记忆存储，可为 null（此时不检索）
     */
    public SemanticRecallCoordinator(IEmbeddingClient embeddingClient,
            ISemanticMemoryStore semanticMemoryStore) {
        this.embeddingClient = embeddingClient;
        this.semanticMemoryStore = semanticMemoryStore;
    }

    /**
     * 按当前输入检索本会话的相关知识。
     *
     * @param conversationId 会话标识，不可为空白
     * @param userInput      当前用户输入
     * @param control        上下文控制配置，不可为 null
     * @return 命中的知识文本列表，非 null
     */
    public List<String> recall(String conversationId, String userInput, ContextControlConfig control) {
        if (embeddingClient == null || semanticMemoryStore == null || control == null
                || control.getRetrievalCount() <= 0 || userInput == null || userInput.trim().isEmpty()) {
            return Collections.emptyList();
        }
        float[] vector = embeddingClient.embed(userInput);
        SemanticMemoryQuery query = new SemanticMemoryQuery(MemorySourceTypeEnum.CONVERSATION,
                conversationId, vector, control.getRetrievalCount(), control.getSimilarityThreshold(),
                System.currentTimeMillis(), null);
        List<MemoryMatch> matches = semanticMemoryStore.search(query);
        List<String> knowledge = new ArrayList<String>();
        for (MemoryMatch match : matches) {
            if (match.getScore() >= control.getSimilarityThreshold()
                    && match.getEntry().getContent() != null) {
                knowledge.add(match.getEntry().getContent());
            }
        }
        return knowledge;
    }
}
