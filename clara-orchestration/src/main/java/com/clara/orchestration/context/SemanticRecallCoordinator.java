package com.clara.orchestration.context;

import com.clara.core.memory.IContextTraceSink;
import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 对话知识找回协调器：用当前输入向量按会话检索被淘汰的历史知识，命中的知识作为补充上下文注入。
 *
 * <p>检索能力由 {@link ISemanticMemoryStore} 提供、向量化由 {@link IEmbeddingClient} 提供；编排层只负责
 * 「何时检索、命中结果如何注入」。检索条数与相似度阈值来自 control 配置。
 *
 * <p>可观测性：每次检索都把「命中几条 / 耗时 / 阈值与候选数 / 命中片段（带相似度）」报给可选的
 * {@link IContextTraceSink}，供控制台观察；未接入宿主时不上报。
 */
public final class SemanticRecallCoordinator {

    /** 知识源标识：宿主据此决定展示成什么名字。 */
    private static final String SOURCE = "semantic_recall";

    /**
     * 查询里的最小相似度：取最小值表示「存储层不预过滤」，阈值判定统一由本类做。
     *
     * <p>为什么不让存储层先按阈值过滤：那样上报出来的只有一个数，分不清「语义库里没有记录」和
     * 「有记录但分数不够」——前者要查写入链路，后者要查嵌入质量或阈值，故障方向完全相反。
     */
    private static final double NO_PRE_FILTER = -1.0d;

    private final IEmbeddingClient embeddingClient;
    private final ISemanticMemoryStore semanticMemoryStore;
    private final IContextTraceSink traceSink;

    /**
     * 创建知识找回协调器。
     *
     * @param embeddingClient    向量化客户端，可为 null（此时不检索）
     * @param semanticMemoryStore 语义记忆存储，可为 null（此时不检索）
     */
    public SemanticRecallCoordinator(IEmbeddingClient embeddingClient,
            ISemanticMemoryStore semanticMemoryStore) {
        this(embeddingClient, semanticMemoryStore, null);
    }

    /**
     * 创建知识找回协调器并接入观测口。
     *
     * @param embeddingClient     向量化客户端，可为 null（此时不检索）
     * @param semanticMemoryStore 语义记忆存储，可为 null（此时不检索）
     * @param traceSink           上下文来源观测口，可为 null（不上报）
     */
    public SemanticRecallCoordinator(IEmbeddingClient embeddingClient,
            ISemanticMemoryStore semanticMemoryStore, IContextTraceSink traceSink) {
        this.embeddingClient = embeddingClient;
        this.semanticMemoryStore = semanticMemoryStore;
        this.traceSink = traceSink;
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
        long started = System.nanoTime();
        float[] vector = embeddingClient.embed(userInput);
        SemanticMemoryQuery query = new SemanticMemoryQuery(MemorySourceTypeEnum.CONVERSATION,
                conversationId, vector, control.getRetrievalCount(), NO_PRE_FILTER,
                System.currentTimeMillis(), null, userInput);
        List<MemoryMatch> matches = semanticMemoryStore.search(query);
        List<String> knowledge = new ArrayList<String>();
        List<String> details = new ArrayList<String>();
        int aboveThreshold = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (MemoryMatch match : matches) {
            bestScore = Math.max(bestScore, match.getScore());
            if (match.getScore() >= control.getSimilarityThreshold()
                    && match.getEntry().getContent() != null) {
                aboveThreshold++;
                knowledge.add(match.getEntry().getContent());
                details.add(String.format(Locale.ROOT, "score=%.3f  %s", match.getScore(),
                        preview(match.getEntry().getContent())));
            }
        }
        details.add(0, summary(control, matches.size(), aboveThreshold, bestScore));
        trace(knowledge.size(), started, details);
        return knowledge;
    }

    /**
     * 汇总一行「召回了几条、其中几条过阈值」。
     *
     * @param control   上下文控制配置，不可为 null
     * @param recalled  存储层实际召回的条数
     * @param above     其中达到相似度阈值的条数
     * @param bestScore 召回结果的最高分；无召回时为负无穷
     * @return 一行可读的汇总文本，非 null
     */
    private static String summary(ContextControlConfig control, int recalled, int above, double bestScore) {
        if (recalled <= 0) {
            return String.format(Locale.ROOT,
                    "召回 0 条（阈值 %.2f，topK %d）——语义库里没有本会话可检索的记录",
                    control.getSimilarityThreshold(), control.getRetrievalCount());
        }
        return String.format(Locale.ROOT, "召回 %d 条 / 过阈值 %d 条（阈值 %.2f，最高分 %.3f）",
                recalled, above, control.getSimilarityThreshold(), bestScore);
    }

    /**
     * 上报本次召回：未接入宿主时直接返回，上报本身失败也不影响对话。
     */
    private void trace(int hits, long startedNanos, List<String> details) {
        if (traceSink == null) {
            return;
        }
        try {
            traceSink.onTrace(SOURCE, hits, (System.nanoTime() - startedNanos) / 1000000L, details);
        } catch (RuntimeException ignored) {
            // 观测是旁路：宿主渲染失败不能影响本轮对话。
        }
    }

    /**
     * 把命中内容压成一行片段：换行与连续空白归一，超长截断。
     */
    private static String preview(String content) {
        String oneLine = content.trim().replaceAll("\\s+", " ");
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "…" : oneLine;
    }
}
