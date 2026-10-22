package com.clara.orchestration.context;

import com.clara.core.memory.IContextSupplementProvider;
import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.core.telemetry.Telemetry;
import com.clara.core.telemetry.TelemetrySpan;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.memory.WindowPolicy;
import com.clara.enums.RoleEnum;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.orchestration.skill.MatchedSkill;
import com.clara.orchestration.skill.SkillMatchCoordinator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 上下文装配器：把「历史恢复、窗口裁剪、超限压缩、检索注入、技能装配」组合成一份本轮请求消息。
 *
 * <p>装配顺序：先按窗口策略裁剪历史；窗口发生淘汰且启用压缩时，把被淘汰的消息组压缩为摘要；随后压缩历史
 * 工具结果（只压当前提问之前的历史部分，控制请求体膨胀）；再完成技能匹配（提示词 + 工具声明）、外部知识
 * 补充（代码知识图谱：先查图谱、图谱里没有再让模型自己查本地文件）与对话知识找回；最后把 system 指令
 * （常驻提示词 + 技能提示词 + 图谱补充信息 + 压缩摘要 + 检索知识）归一化到消息首位。
 *
 * <p>提示词分两类：{@code basePrompts} 是常驻的，每轮原样注入，承载「读代码先查图谱」这类与输入无关的
 * 固定约定；技能提示词是按输入相似度检索出来的，承载随输入变化的用法说明。前者靠检索注入会因阈值与
 * 条数上限时有时无，故单独走一条通道。
 */
public final class ContextAssembler {

    private final WindowCompressionCoordinator windowCompressionCoordinator;
    private final SemanticRecallCoordinator semanticRecallCoordinator;
    private final IContextSupplementProvider supplementProvider;
    private final SkillMatchCoordinator skillMatchCoordinator;

    /**
     * 常驻提示词：每轮原样拼进 system 指令，不参与按输入的相似度检索；不可变，可能为空。
     */
    private final List<String> basePrompts;
    /** 链路追踪入口；未接观测端时为空实现。 */
    private final Telemetry telemetry;

    /**
     * 创建上下文装配器（不注入外部知识补充）。
     *
     * @param windowCompressionCoordinator 窗口压缩协调器，不可为 null
     * @param semanticRecallCoordinator    知识找回协调器，不可为 null
     * @param skillMatchCoordinator        技能匹配协调器，不可为 null
     */
    public ContextAssembler(WindowCompressionCoordinator windowCompressionCoordinator,
                            SemanticRecallCoordinator semanticRecallCoordinator,
                            SkillMatchCoordinator skillMatchCoordinator) {
        this(windowCompressionCoordinator, semanticRecallCoordinator, null, skillMatchCoordinator, null,
                Telemetry.noop());
    }


    /**
     * 创建上下文装配器并指定常驻提示词。
     *
     * @param windowCompressionCoordinator 窗口压缩协调器，不可为 null
     * @param semanticRecallCoordinator    知识找回协调器，不可为 null
     * @param supplementProvider           外部知识补充提供者（如代码知识图谱）；可为 null（不补充）
     * @param skillMatchCoordinator        技能匹配协调器，不可为 null
     * @param basePrompts                  常驻提示词（每轮原样注入，不参与检索）；可为 null（视为无）
     */
    public ContextAssembler(WindowCompressionCoordinator windowCompressionCoordinator,
                            SemanticRecallCoordinator semanticRecallCoordinator,
                            IContextSupplementProvider supplementProvider,
                            SkillMatchCoordinator skillMatchCoordinator,
                            List<String> basePrompts) {
        this(windowCompressionCoordinator, semanticRecallCoordinator, supplementProvider,
                skillMatchCoordinator, basePrompts, Telemetry.noop());
    }

    /**
     * 创建上下文装配器并接入链路追踪。
     *
     * @param windowCompressionCoordinator 窗口压缩协调器，不可为 null
     * @param semanticRecallCoordinator    知识找回协调器，不可为 null
     * @param supplementProvider           外部知识补充提供者（如代码知识图谱）；可为 null（不补充）
     * @param skillMatchCoordinator        技能匹配协调器，不可为 null
     * @param basePrompts                  常驻提示词（每轮原样注入，不参与检索）；可为 null（视为无）
     * @param telemetry                    链路追踪入口，可为 null（按空实现处理）
     */
    public ContextAssembler(WindowCompressionCoordinator windowCompressionCoordinator,
                            SemanticRecallCoordinator semanticRecallCoordinator,
                            IContextSupplementProvider supplementProvider,
                            SkillMatchCoordinator skillMatchCoordinator,
                            List<String> basePrompts, Telemetry telemetry) {
        if (windowCompressionCoordinator == null || semanticRecallCoordinator == null
                || skillMatchCoordinator == null) {
            throw new IllegalArgumentException("context assembler dependencies are invalid");
        }
        this.windowCompressionCoordinator = windowCompressionCoordinator;
        this.semanticRecallCoordinator = semanticRecallCoordinator;
        this.supplementProvider = supplementProvider;
        this.skillMatchCoordinator = skillMatchCoordinator;
        this.basePrompts = basePrompts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(basePrompts));
        this.telemetry = telemetry == null ? Telemetry.noop() : telemetry;
    }

    /**
     * 装配本轮下发给模型的消息与工具声明。
     *
     * @param windowMemory 会话窗口记忆，不可为 null
     * @param control      上下文控制配置；为 null 时使用默认配置
     * @param estimator    token 估算器，可为 null（仅按百分比维度限制时需要）
     * @return 装配结果，非 null
     */
    public AssembledContext assemble(IWindowMemory windowMemory, ContextControlConfig control, ITokenCountEstimator estimator) {
        return assemble(windowMemory, control, estimator, Collections.emptyList());
    }

    /**
     * 装配本轮下发给模型的消息与工具声明，并追加本轮临时提示词（如已校验的执行计划）。
     *
     * @param windowMemory 会话窗口记忆，不可为 null
     * @param control      上下文控制配置；为 null 时使用默认配置
     * @param estimator    token 估算器，可为 null（仅按百分比维度限制时需要）
     * @param extraPrompts 本轮临时提示词，可为 null（视为无）
     * @return 装配结果，非 null
     */
    public AssembledContext assemble(IWindowMemory windowMemory, ContextControlConfig control,
            ITokenCountEstimator estimator, List<String> extraPrompts) {
        if (windowMemory == null) {
            throw new IllegalArgumentException("windowMemory must not be null");
        }
        ContextControlConfig effective = control == null ? ContextControlConfig.defaults() : control;
        WindowPolicy policy = new WindowPolicy(effective.getContextWindowTokens(),
                effective.getWindowMaxMessages(), effective.getWindowMaxSizeBytes(),
                effective.getWindowMaxTokensPercent(), effective.getRecentTurnsKept());

        List<IContent> history;
        List<IContent> windowed;
        List<IContent> prepared;
        try (TelemetrySpan span = telemetry.startSpan("context.window", TelemetrySpanKindEnum.CONTEXT)) {
            history = HistorySanitizer.dropInvalidAssistantMessages(windowMemory.messages());
            windowed = HistorySanitizer.dropInvalidAssistantMessages(windowMemory.window(policy, estimator));
            // 按「总量预算 + 最近优先」压缩工具结果：历史与本轮一视同仁，只保留最近一段原样，其余按预算截断。
            // 注意：淘汰判定仍用未压缩的 windowed（按对象同一性比对），避免压缩后的新对象被误判为淘汰。
            prepared = ToolOutputCompactor.compact(windowed,
                    effective.getToolOutputMaxChars(), effective.getToolOutputBudgetChars());
            span.attribute("history_messages", history.size())
                    .attribute("window_messages", windowed.size())
                    .attribute("prepared_messages", prepared.size())
                    .attribute("recent_turns_kept", effective.getRecentTurnsKept());
        }
        String userInput = latestUserInput(windowMemory.conversationId(), history);

        List<IContent> systemParts = new ArrayList<>();
        if (effective.isCompressEnabled()) {
            try (TelemetrySpan span = telemetry.startSpan("context.compress", TelemetrySpanKindEnum.CONTEXT)) {
                List<IContent> evictedMessages = evicted(history, windowed);
                IContent summary = windowCompressionCoordinator.summarize(evictedMessages);
                if (summary != null) {
                    systemParts.add(summary);
                }
                span.attribute("summary", summary != null)
                        .attribute("reason", "window")
                        .attribute("evicted", evictedMessages.size());
            }
        }
        MatchedSkill matched;
        try (TelemetrySpan span = telemetry.startSpan("context.skill", TelemetrySpanKindEnum.CONTEXT)) {
            matched = skillMatchCoordinator.match(userInput, effective);
            span.attribute("prompts", matched.getPrompts().size())
                    .attribute("tools", matched.getTools().size());
        }
        // 外部知识补充：先问一次代码知识图谱，命中就把「既有事实」直接放进 system 指令；没命中返回空，
        // 模型自然走工具查本地文件那条路（补充提供者自身兜底，失败不阻断本轮）。
        List<String> supplement;
        try (TelemetrySpan span = telemetry.startSpan("context.code_graph", TelemetrySpanKindEnum.CONTEXT)) {
            supplement = supplementProvider == null
                    ? Collections.emptyList() : supplementProvider.supplement(userInput);
            span.attribute("items", supplement.size())
                    .attribute("chars", chars(supplement));
        }
        // 召回被窗口淘汰的历史知识：把本轮真正要下发的消息一并传进去，命中的内容若模型本来就看得到（本轮提问、
        // 刚返回的工具结果）会被丢弃，不重复塞进 system 指令。
        List<String> recall;
        try (TelemetrySpan span = telemetry.startSpan("context.recall", TelemetrySpanKindEnum.CONTEXT)) {
            recall = semanticRecallCoordinator.recall(windowMemory.conversationId(), userInput,
                    effective, prepared);
            span.attribute("items", recall.size())
                    .attribute("chars", chars(recall))
                    .attribute("source", "semantic_recall");
        }

        String systemText = systemText(systemParts, extraPrompts, basePrompts, matched.getPrompts(), supplement, recall);
        List<IContent> messages = new ArrayList<>();
        if (!systemText.isEmpty()) {
            messages.add(new SystemContent(systemText));
        }
        messages.addAll(prepared);
        return new AssembledContext(messages, matched.getTools());
    }

    /**
     * 取完整历史中最早被窗口淘汰的消息（窗口裁剪从最旧开始整组淘汰）。
     */
    private static List<IContent> evicted(List<IContent> history, List<IContent> windowed) {
        Set<IContent> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        kept.addAll(windowed);
        List<IContent> evicted = new ArrayList<>();
        for (IContent message : history) {
            if (!kept.contains(message)) {
                evicted.add(message);
            }
        }
        return evicted;
    }

    /**
     * 取完整历史中最后一条用户输入文本作为检索与匹配的查询。
     */
    private static String latestUserInput(String conversationId, List<IContent> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            IContent message = history.get(index);
            if (message != null && message.getRoleType() == RoleEnum.USER && message instanceof UserContent) {
                return ((UserContent) message).getContent();
            }
        }
        return conversationId;
    }

    /**
     * 归一化 system 指令文本：常驻提示词、技能提示词、图谱补充信息、压缩摘要、检索知识按序拼接。
     */
    private static String systemText(List<IContent> systemParts, List<String> extraPrompts,
            List<String> basePrompts, List<String> prompts, List<String> supplement, List<String> recall) {
        StringBuilder text = new StringBuilder();
        if (extraPrompts != null) {
            for (String prompt : extraPrompts) {
                append(text, prompt);
            }
        }
        for (String prompt : basePrompts) {
            append(text, prompt);
        }
        for (String prompt : prompts) {
            append(text, prompt);
        }
        for (String knowledge : supplement) {
            append(text, knowledge);
        }
        for (IContent part : systemParts) {
            append(text, part.instructionText());
        }
        for (String knowledge : recall) {
            append(text, knowledge);
        }
        return text.toString();
    }

    /**
     * 统计补充信息字符数，用于判断检索是否把上下文撑大。
     *
     * @param lines 文本行，可为 null
     * @return 字符总数，非负
     */
    private static int chars(List<String> lines) {
        if (lines == null) {
            return 0;
        }
        int total = 0;
        for (String line : lines) {
            total += line == null ? 0 : line.length();
        }
        return total;
    }

    private static void append(StringBuilder text, String segment) {
        if (segment == null || segment.trim().isEmpty()) {
            return;
        }
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(segment);
    }

}
