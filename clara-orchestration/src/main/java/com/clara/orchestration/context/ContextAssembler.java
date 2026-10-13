package com.clara.orchestration.context;

import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.memory.WindowPolicy;
import com.clara.enums.RoleEnum;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.orchestration.skill.SkillMatchCoordinator;
import com.clara.dto.tool.ToolContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 上下文装配器：把「历史恢复、窗口裁剪、超限压缩、检索注入、技能装配」组合成一份本轮请求消息。
 *
 * <p>装配顺序：先按窗口策略裁剪历史；窗口发生淘汰且启用压缩时，把被淘汰的消息组压缩为摘要；随后压缩历史
 * 工具结果（只压当前提问之前的历史部分，控制请求体膨胀）；再完成技能匹配（提示词 + 工具声明）与对话知识
 * 找回；最后把 system 指令（技能提示词 + 压缩摘要 + 检索知识）归一化到消息首位。
 */
public final class ContextAssembler {

    private final WindowCompressionCoordinator windowCompressionCoordinator;
    private final SemanticRecallCoordinator semanticRecallCoordinator;
    private final SkillMatchCoordinator skillMatchCoordinator;

    /**
     * 创建上下文装配器。
     *
     * @param windowCompressionCoordinator 窗口压缩协调器，不可为 null
     * @param semanticRecallCoordinator    知识找回协调器，不可为 null
     * @param skillMatchCoordinator        技能匹配协调器，不可为 null
     */
    public ContextAssembler(WindowCompressionCoordinator windowCompressionCoordinator,
            SemanticRecallCoordinator semanticRecallCoordinator,
            SkillMatchCoordinator skillMatchCoordinator) {
        if (windowCompressionCoordinator == null || semanticRecallCoordinator == null
                || skillMatchCoordinator == null) {
            throw new IllegalArgumentException("context assembler dependencies are invalid");
        }
        this.windowCompressionCoordinator = windowCompressionCoordinator;
        this.semanticRecallCoordinator = semanticRecallCoordinator;
        this.skillMatchCoordinator = skillMatchCoordinator;
    }

    /**
     * 装配本轮下发给模型的消息与工具声明。
     *
     * @param windowMemory 会话窗口记忆，不可为 null
     * @param control      上下文控制配置；为 null 时使用默认配置
     * @param estimator    token 估算器，可为 null（仅按百分比维度限制时需要）
     * @return 装配结果，非 null
     */
    public AssembledContext assemble(IWindowMemory windowMemory, ContextControlConfig control,
            ITokenCountEstimator estimator) {
        if (windowMemory == null) {
            throw new IllegalArgumentException("windowMemory must not be null");
        }
        ContextControlConfig effective = control == null ? ContextControlConfig.defaults() : control;
        WindowPolicy policy = new WindowPolicy(effective.getContextWindowTokens(),
                effective.getWindowMaxMessages(), effective.getWindowMaxSizeBytes(),
                effective.getWindowMaxTokensPercent());

        List<IContent> history = HistorySanitizer.dropInvalidAssistantMessages(windowMemory.messages());
        List<IContent> windowed = HistorySanitizer.dropInvalidAssistantMessages(windowMemory.window(policy, estimator));
        // 压缩历史工具结果（只压当前提问之前的历史部分，本轮工具结果原样保留），控制请求体膨胀。
        // 注意：淘汰判定仍用未压缩的 windowed（按对象同一性比对），避免压缩后的新对象被误判为淘汰。
        List<IContent> prepared = ToolOutputCompactor.compact(windowed, effective.getToolOutputMaxChars());
        String userInput = latestUserInput(windowMemory.conversationId(), history);

        List<IContent> systemParts = new ArrayList<IContent>();
        if (effective.isCompressEnabled()) {
            IContent summary = windowCompressionCoordinator.summarize(evicted(history, windowed));
            if (summary != null) {
                systemParts.add(summary);
            }
        }
        SkillMatchCoordinator.MatchedSkill matched = skillMatchCoordinator.match(userInput, effective);
        List<String> recall = semanticRecallCoordinator.recall(windowMemory.conversationId(), userInput,
                effective);

        String systemText = systemText(systemParts, matched.getPrompts(), recall);
        List<IContent> messages = new ArrayList<IContent>();
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
        Set<IContent> kept = Collections.newSetFromMap(new IdentityHashMap<IContent, Boolean>());
        kept.addAll(windowed);
        List<IContent> evicted = new ArrayList<IContent>();
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
            if (message != null && message.getRoleType() == RoleEnum.USER
                    && message instanceof UserContent) {
                return ((UserContent) message).getContent();
            }
        }
        return conversationId;
    }

    /**
     * 归一化 system 指令文本：技能提示词、压缩摘要、检索知识按序拼接。
     */
    private static String systemText(List<IContent> systemParts, List<String> prompts,
            List<String> recall) {
        StringBuilder text = new StringBuilder();
        for (String prompt : prompts) {
            append(text, prompt);
        }
        for (IContent part : systemParts) {
            append(text, part.instructionText());
        }
        for (String knowledge : recall) {
            append(text, knowledge);
        }
        return text.toString();
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

    /**
     * 装配结果：本轮请求消息（system 指令居首）与工具声明。
     */
    public static final class AssembledContext {

        private final List<IContent> messages;
        private final List<ToolContext> tools;

        AssembledContext(List<IContent> messages, List<ToolContext> tools) {
            this.messages = Collections.unmodifiableList(new ArrayList<IContent>(messages));
            this.tools = Collections.unmodifiableList(new ArrayList<ToolContext>(tools));
        }

        public List<IContent> getMessages() {
            return messages;
        }

        public List<ToolContext> getTools() {
            return tools;
        }
    }
}
