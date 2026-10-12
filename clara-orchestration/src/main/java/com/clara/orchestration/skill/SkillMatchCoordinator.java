package com.clara.orchestration.skill;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.core.skill.IPromptProvider;
import com.clara.core.skill.IToolCatalog;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.dto.tool.ToolContext;
import com.clara.enums.memory.MemorySourceTypeEnum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 技能匹配协调器：用用户输入向量检索技能描述，选出本轮要注入的提示词，并附上当前全部可用工具声明。
 *
 * <p>检索走 {@link ISemanticMemoryStore}（来源限定 {@code PROMPT}），提示词取回走 {@link IPromptProvider}；
 * 命中结果按相似度过滤、同技能去重后交由 {@code ContextAssembler} 注入（提示词并入 system）。
 *
 * <p>工具声明不走语义匹配：由 {@link IToolCatalog} 全量提供（来源为技能注册表中已注册的全部工具），
 * 交由模型自主选择调用，避免占位哈希嵌入无语义导致工具无法下发。
 */
public final class SkillMatchCoordinator {

    private final IEmbeddingClient embeddingClient;
    private final ISemanticMemoryStore semanticMemoryStore;
    private final IPromptProvider promptProvider;
    private final IToolCatalog toolCatalog;

    /**
     * 创建技能匹配协调器（不提供工具声明）。
     *
     * @param embeddingClient     向量化客户端，可为 null（此时不匹配）
     * @param semanticMemoryStore 语义记忆存储，可为 null（此时不匹配）
     * @param promptProvider      提示词提供方，可为 null（此时不取回提示词）
     */
    public SkillMatchCoordinator(IEmbeddingClient embeddingClient,
            ISemanticMemoryStore semanticMemoryStore, IPromptProvider promptProvider) {
        this(embeddingClient, semanticMemoryStore, promptProvider, null);
    }

    /**
     * 创建技能匹配协调器。
     *
     * @param embeddingClient     向量化客户端，可为 null（此时不匹配）
     * @param semanticMemoryStore 语义记忆存储，可为 null（此时不匹配）
     * @param promptProvider      提示词提供方，可为 null（此时不取回提示词）
     * @param toolCatalog         可用工具目录，可为 null（此时不下发任何工具声明）
     */
    public SkillMatchCoordinator(IEmbeddingClient embeddingClient,
            ISemanticMemoryStore semanticMemoryStore, IPromptProvider promptProvider,
            IToolCatalog toolCatalog) {
        this.embeddingClient = embeddingClient;
        this.semanticMemoryStore = semanticMemoryStore;
        this.promptProvider = promptProvider;
        this.toolCatalog = toolCatalog;
    }

    /**
     * 按当前用户输入匹配技能提示词与工具声明。
     *
     * @param userInput 当前用户输入
     * @param control   上下文控制配置，不可为 null
     * @return 匹配结果，非 null（无命中时为空结果）
     */
    public MatchedSkill match(String userInput, ContextControlConfig control) {
        List<String> prompts = new ArrayList<String>();
        if (control != null && control.getRetrievalCount() > 0
                && embeddingClient != null && semanticMemoryStore != null
                && userInput != null && !userInput.trim().isEmpty()) {
            float[] vector = embeddingClient.embed(userInput);
            for (MemoryMatch match : search(MemorySourceTypeEnum.PROMPT, vector, control)) {
                if (promptProvider != null && match.getEntry().getSourceId() != null) {
                    prompts.add(promptProvider.prompt(match.getEntry().getSourceId()));
                }
            }
        }
        List<ToolContext> tools = toolCatalog == null
                ? Collections.<ToolContext>emptyList() : toolCatalog.availableTools();
        return new MatchedSkill(prompts, tools);
    }

    /**
     * 按来源类型检索并过滤低于阈值的命中、同来源去重。
     */
    private List<MemoryMatch> search(MemorySourceTypeEnum sourceType, float[] vector,
            ContextControlConfig control) {
        SemanticMemoryQuery query = new SemanticMemoryQuery(sourceType, null, vector,
                control.getRetrievalCount(), control.getSimilarityThreshold(),
                System.currentTimeMillis(), null);
        List<MemoryMatch> matches = semanticMemoryStore.search(query);
        List<MemoryMatch> filtered = new ArrayList<MemoryMatch>();
        Set<String> seen = new LinkedHashSet<String>();
        for (MemoryMatch match : matches) {
            String sourceId = match.getEntry().getSourceId();
            if (match.getScore() >= control.getSimilarityThreshold() && sourceId != null
                    && seen.add(sourceId)) {
                filtered.add(match);
            }
        }
        return filtered;
    }

    /**
     * 技能匹配结果：本轮要注入的提示词与工具声明。
     */
    public static final class MatchedSkill {

        private final List<String> prompts;
        private final List<ToolContext> tools;

        MatchedSkill(List<String> prompts, List<ToolContext> tools) {
            this.prompts = Collections.unmodifiableList(new ArrayList<String>(prompts));
            this.tools = Collections.unmodifiableList(new ArrayList<ToolContext>(tools));
        }

        public List<String> getPrompts() {
            return prompts;
        }

        public List<ToolContext> getTools() {
            return tools;
        }
    }
}
