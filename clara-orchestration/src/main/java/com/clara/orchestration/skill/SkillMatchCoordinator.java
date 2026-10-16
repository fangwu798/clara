package com.clara.orchestration.skill;

import com.clara.core.memory.IContextTraceSink;
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
import java.util.Locale;
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

    /** 知识源标识：提示词向量匹配，控制台据此决定展示成什么标签。 */
    private static final String SOURCE = "prompt_match";

    /**
     * 查询里的最小相似度：取最小值表示「存储层不预过滤」，阈值判定统一由本类做。
     */
    private static final double NO_PRE_FILTER = -1.0d;

    private final IEmbeddingClient embeddingClient;
    private final ISemanticMemoryStore semanticMemoryStore;
    private final IPromptProvider promptProvider;
    private final IToolCatalog toolCatalog;
    private final IContextTraceSink traceSink;

    /**
     * 创建技能匹配协调器（不提供工具声明）。
     *
     * @param embeddingClient     向量化客户端，可为 null（此时不匹配）
     * @param semanticMemoryStore 语义记忆存储，可为 null（此时不匹配）
     * @param promptProvider      提示词提供方，可为 null（此时不取回提示词）
     */
    public SkillMatchCoordinator(IEmbeddingClient embeddingClient,
            ISemanticMemoryStore semanticMemoryStore, IPromptProvider promptProvider) {
        this(embeddingClient, semanticMemoryStore, promptProvider, null, null);
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
        this(embeddingClient, semanticMemoryStore, promptProvider, toolCatalog, null);
    }

    /**
     * 构建技能匹配协调器，并带上知识源观测口。
     *
     * @param embeddingClient     向量客户端，为 null 时不匹配
     * @param semanticMemoryStore 语义存储，为 null 时不匹配
     * @param promptProvider      提示词提供者，为 null 时不取提示词
     * @param toolCatalog         工具目录，为 null 时不下发任何工具
     * @param traceSink           知识源观测口，为 null 时不上报
     */
    public SkillMatchCoordinator(IEmbeddingClient embeddingClient,
            ISemanticMemoryStore semanticMemoryStore, IPromptProvider promptProvider,
            IToolCatalog toolCatalog, IContextTraceSink traceSink) {
        this.embeddingClient = embeddingClient;
        this.semanticMemoryStore = semanticMemoryStore;
        this.promptProvider = promptProvider;
        this.toolCatalog = toolCatalog;
        this.traceSink = traceSink;
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
        List<String> details = new ArrayList<String>();
        long started = System.nanoTime();
        if (control != null && control.getRetrievalCount() > 0
                && embeddingClient != null && semanticMemoryStore != null
                && userInput != null && !userInput.trim().isEmpty()) {
            float[] vector = embeddingClient.embed(userInput);
            List<MemoryMatch> candidates = search(MemorySourceTypeEnum.PROMPT, vector, control, userInput);
            int aboveThreshold = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (MemoryMatch candidate : candidates) {
                bestScore = Math.max(bestScore, candidate.getScore());
                if (candidate.getScore() >= control.getSimilarityThreshold()) {
                    aboveThreshold++;
                }
            }
            // 明细先报「召回几条 / 过阈值几条 / 最高分」：只报一个数会分不清「库里没数据」与「分数不够」
            details.add(summary(candidates.size(), aboveThreshold, control, bestScore));
            Set<String> seen = new LinkedHashSet<String>();
            for (MemoryMatch match : candidates) {
                String sourceId = match.getEntry().getSourceId();
                if (promptProvider == null || sourceId == null
                        || match.getScore() < control.getSimilarityThreshold()
                        || !seen.add(sourceId)) {
                    continue;
                }
                prompts.add(promptProvider.prompt(sourceId));
                details.add(String.format(Locale.ROOT, "score=%.3f  %s", match.getScore(), sourceId));
            }
        } else {
            details.add("本轮未开启提示词检索（retrievalCount <= 0 或依赖缺失）");
        }
        trace(prompts.size(), started, details);
        List<ToolContext> tools = toolCatalog == null
                ? Collections.<ToolContext>emptyList() : toolCatalog.availableTools();
        return new MatchedSkill(prompts, tools);
    }

    /**
     * 上报提示词匹配：未配置观测口时直接返回，上报失败也不影响本轮对话。
     *
     * @param hits         实际注入的提示词条数，0 表示未命中
     * @param startedNanos 开始计时的纳秒值
     * @param details      明细行（阈值 / 候选数 / 每条命中的分数与 id）
     */
    private void trace(int hits, long startedNanos, List<String> details) {
        if (traceSink == null) {
            return;
        }
        try {
            traceSink.onTrace(SOURCE, hits, (System.nanoTime() - startedNanos) / 1000000L, details);
        } catch (RuntimeException ignored) {
            // 观测只是旁路：渲染失败不影响本轮对话。
        }
    }

    /**
     * 按来源类型检索原始候选：不做阈值过滤与同源去重，调用方要能看到「候选几条、为什么被刷掉」。
     *
     * <p>查询一并带上原始文本：全文检索后端（Milvus BM25）直接拿文本检索，
     * 不读向量；向量后端会忽略它。
     */
    private List<MemoryMatch> search(MemorySourceTypeEnum sourceType, float[] vector,
            ContextControlConfig control, String queryText) {
        SemanticMemoryQuery query = new SemanticMemoryQuery(sourceType, null, vector,
                control.getRetrievalCount(), NO_PRE_FILTER,
                System.currentTimeMillis(), null, queryText);
        return semanticMemoryStore.search(query);
    }

    /**
     * 汇总一行「召回了几条提示词候选、其中几条过阈值」。
     *
     * @param recalled   存储层实际召回的条数
     * @param above      其中达到相似度阈值的条数
     * @param control    上下文控制配置，不可为 null
     * @param bestScore  召回结果的最高分；无召回时为负无穷
     * @return 一行可读的汇总文本，非 null
     */
    private static String summary(int recalled, int above, ContextControlConfig control, double bestScore) {
        if (recalled <= 0) {
            return String.format(Locale.ROOT,
                    "召回 0 条（阈值 %.2f，topK %d）——语义库里没有提示词候选",
                    control.getSimilarityThreshold(), control.getRetrievalCount());
        }
        return String.format(Locale.ROOT, "召回 %d 条 / 过阈值 %d 条（阈值 %.2f，最高分 %.3f）",
                recalled, above, control.getSimilarityThreshold(), bestScore);
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
