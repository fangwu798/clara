package com.clara.orchestration.context;

import com.clara.core.memory.ConversationMemoryText;
import com.clara.core.memory.IContextTraceSink;
import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.core.telemetry.Telemetry;
import com.clara.core.telemetry.TelemetrySpan;
import com.clara.core.message.IContent;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 对话知识找回协调器：用当前输入向量按会话检索被淘汰的历史知识，命中的知识作为补充上下文注入。
 *
 * <p>检索能力由 {@link ISemanticMemoryStore} 提供、向量化由 {@link IEmbeddingClient} 提供；编排层只负责
 * 「何时检索、命中结果如何注入」。检索条数与相似度阈值来自 control 配置。
 *
 * <p>可观测性：每次检索都把「命中几条 / 耗时 / 阈值与候选数 / 命中片段（带相似度）」报给可选的
 * {@link IContextTraceSink}，供控制台观察；未接入宿主时不上报。
 * <p>判重：命中内容若本轮请求里已经带着（本轮提问、最近窗口内的历史、刚返回的工具结果），直接静默丢弃、
 * 不进补充信息，也不产生逐条「丢弃」日志——最近 {@code recentTurnsKept} 轮由窗口保证可见，本类只补充
 * 窗口外的历史。与写入端共用 {@link ConversationMemoryText} 折算文本，一个字不差才算重复。
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

    /**
     * 查询候选上限。召回时会把「本轮可见消息数」加进 topK，避免最近窗口内的重复命中占满候选位，
     * 导致真正需要补回的旧知识根本进不了候选集；同时用一个上限约束极端长窗口的查询成本。
     */
    private static final int MAX_CANDIDATES = 100;

    private final IEmbeddingClient embeddingClient;
    private final ISemanticMemoryStore semanticMemoryStore;
    private final IContextTraceSink traceSink;
    /** 链路追踪入口；未接入时为空实现。 */
    private final Telemetry telemetry;

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
        this(embeddingClient, semanticMemoryStore, traceSink, Telemetry.noop());
    }

    /**
     * 创建知识找回协调器并接入链路追踪。
     *
     * @param embeddingClient     向量化客户端，可为 null（此时不检索）
     * @param semanticMemoryStore 语义记忆存储，可为 null（此时不检索）
     * @param traceSink           上下文来源观测口，可为 null（不上报）
     * @param telemetry           链路追踪入口，可为 null（按空实现处理）
     */
    public SemanticRecallCoordinator(IEmbeddingClient embeddingClient,
            ISemanticMemoryStore semanticMemoryStore, IContextTraceSink traceSink, Telemetry telemetry) {
        this.embeddingClient = embeddingClient;
        this.semanticMemoryStore = semanticMemoryStore;
        this.traceSink = traceSink;
        this.telemetry = telemetry == null ? Telemetry.noop() : telemetry;
    }

    /**
     * 按当前输入检索本会话的相关知识（无本轮上下文可比，等价于判重集合为空）。
     *
     * @param conversationId 会话标识，不可为空白
     * @param userInput      当前用户输入
     * @param control        上下文控制配置，不可为 null
     * @return 命中的知识文本列表，非 null
     */
    public List<String> recall(String conversationId, String userInput, ContextControlConfig control) {
        return recall(conversationId, userInput, control, Collections.emptyList());
    }

    /**
     * 按当前输入检索本会话的相关知识，并丢弃「本轮请求里已经带着的」命中。
     *
     * <p>为什么必须丢弃：同一条消息写进语义库时往往还在本轮上下文里——本轮提问本身、刚返回的工具结果都算。
     * 命中它们等于把模型刚看过的内容再抄一份塞进 system 指令：白占 token，还会让「召回命中了」这个观测结论失真。
     *
     * <p>判重口径：只比文本、不比分数，与写入端共用 {@link ConversationMemoryText} 折算文本，一个字不差才算重复；
     * 因此压缩或截断过的历史消息（正文已经变了）仍会被召回，「找回被淘汰内容」这个本职不受影响。
     *
     * @param conversationId  会话标识，不可为空白
     * @param userInput       当前用户输入
     * @param control         上下文控制配置，不可为 null
     * @param visibleMessages 本轮请求将要下发的消息（含本轮提问与其后的工具调用、工具结果），可为 null
     * @return 命中的知识文本列表，非 null
     */
    public List<String> recall(String conversationId, String userInput, ContextControlConfig control, List<IContent> visibleMessages) {
        if (embeddingClient == null || semanticMemoryStore == null || control == null || control.getRetrievalCount() <= 0 || userInput == null || userInput.trim().isEmpty()) {
            return Collections.emptyList();
        }
        long started = System.nanoTime();
        float[] vector = embeddingClient.embed(userInput);
        SemanticMemoryQuery query = SemanticMemoryQuery.builder(vector,
                candidateLimit(control, visibleMessages), NO_PRE_FILTER, System.currentTimeMillis())
                .sourceType(MemorySourceTypeEnum.CONVERSATION)
                .conversationId(conversationId)
                .queryText(userInput)
                .build();
        List<MemoryMatch> matches = semanticMemoryStore.search(query);
        Set<String> visible = visibleTexts(visibleMessages);
        List<String> knowledge = new ArrayList<>();
        List<String> details = new ArrayList<>();
        int considered = 0;
        int aboveThreshold = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (MemoryMatch match : matches) {
            String content = match.getEntry().getContent();
            if (content == null || visible.contains(content)) {
                // 最近窗口里已经有的内容：静默跳过，不产生「丢弃」日志。
                continue;
            }
            considered++;
            bestScore = Math.max(bestScore, match.getScore());
            if (match.getScore() >= control.getSimilarityThreshold()) {
                aboveThreshold++;
                knowledge.add(content);
                details.add(String.format(Locale.ROOT, "score=%.3f  %s", match.getScore(),
                        preview(content)));
            }
        }
        TelemetrySpan span = telemetry.currentSpan();
        if (span != null) {
            span.attribute("candidates", matches.size())
                    .attribute("above_threshold", aboveThreshold)
                    .attribute("source", SOURCE);
        }
        details.add(0, summary(control, matches.size(), considered, aboveThreshold, bestScore));
        trace(knowledge.size(), started, details);
        return knowledge;
    }

    /**
     * 汇总一行：只报告「窗口外候选」的条数与过阈值条数；窗口内重复候选完全静默。
     *
     * @param control   上下文控制配置，不可为 null
     * @param storeHits 存储层返回的候选条数
     * @param considered 去掉窗口内重复后的窗口外候选条数
     * @param above     窗口外候选中达到相似度阈值的条数
     * @param bestScore 窗口外候选中的最高分；无候选时为负无穷
     * @return 一行可读的汇总文本，非 null
     */
    private static String summary(ContextControlConfig control, int storeHits, int considered, int above,
            double bestScore) {
        int topK = control.getRetrievalCount();
        double threshold = control.getSimilarityThreshold();
        if (storeHits <= 0) {
            return String.format(Locale.ROOT,
                    "召回 0 条（阈值 %.2f，topK %d）——语义库里没有本会话可检索的记录", threshold, topK);
        }
        if (considered <= 0) {
            return String.format(Locale.ROOT,
                    "窗口外召回 0 条（阈值 %.2f，topK %d）——最近窗口已覆盖全部候选", threshold, topK);
        }
        return String.format(Locale.ROOT, "窗口外召回 %d 条 / 过阈值 %d 条（阈值 %.2f，最高分 %.3f）",
                considered, above, threshold, bestScore);
    }

    /**
     * 计算本次查询的候选条数：基础 topK 之外，再为「本轮可见消息」预留同样多的候选位。
     *
     * <p>窗口里已有的消息会被静默去重；如果 topK 只取 retrievalCount，最近几条高相似度命中会把候选位
     * 全部占满，旧知识无法进入结果集。预留候选位后，去重仍然只保留真正需要补回的内容。
     *
     * @param control         上下文控制配置，不可为 null
     * @param visibleMessages 本轮可见消息，可为 null
     * @return 候选条数，至少为 retrievalCount
     */
    private static int candidateLimit(ContextControlConfig control, List<IContent> visibleMessages) {
        int base = control.getRetrievalCount();
        int visible = visibleMessages == null ? 0 : visibleMessages.size();
        long expanded = (long) base + visible;
        int cap = Math.max(base, MAX_CANDIDATES);
        return (int) Math.min(Math.max(expanded, base), cap);
    }

    /**
     * 把本轮要下发的消息折算成「入库文本」集合，供判重。
     *
     * @param messages 本轮消息，可为 null
     * @return 入库文本集合，非 null（无可比文本时为空集）
     */
    private static Set<String> visibleTexts(List<IContent> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> texts = new HashSet<>();
        for (IContent message : messages) {
            String text = ConversationMemoryText.of(message);
            if (text != null) {
                texts.add(text);
            }
        }
        return texts;
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
