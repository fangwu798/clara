package com.clara.skill.prompt;

import com.clara.core.skill.IPromptProvider;
import com.clara.dto.skill.PromptDefinition;
import com.clara.dto.skill.PromptSegment;
import com.clara.skill.SkillErrors;
import com.clara.skill.registry.SkillRegistry;

/**
 * 提示词提供器：按标识取回提示词定义文本，并提供内置总结提示词（可被运行目录同名覆盖）。
 */
public final class DefaultPromptProvider implements IPromptProvider {

    /**
     * 内置总结提示词的标识；运行目录 {@code config/skill} 下的同名提示词可覆盖内置默认。
     */
    public static final String SUMMARIZE_PROMPT_ID = "summarize";

    private static final String DEFAULT_SUMMARIZE_PROMPT =
            "你是一名上下文压缩助手。请把下面给出的对话历史压缩为一段简洁的摘要，"
            + "保留关键事实、用户的明确意图、已完成的操作与尚未完成的事项，去掉冗余与客套。"
            + "摘要需用第三人称、按时间顺序组织，长度控制在 500 字以内。";

    private final SkillRegistry registry;

    /**
     * 创建提示词提供器。
     *
     * @param registry 技能注册表，可为 null（仅提供内置总结提示词）
     */
    public DefaultPromptProvider(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String prompt(String promptId) {
        PromptDefinition definition = registry == null ? null : registry.findPrompt(promptId);
        if (definition == null) {
            throw SkillErrors.skillNotFound(promptId);
        }
        return render(definition);
    }

    @Override
    public String summarizePrompt() {
        PromptDefinition definition = registry == null ? null : registry.findPrompt(SUMMARIZE_PROMPT_ID);
        return definition == null ? DEFAULT_SUMMARIZE_PROMPT : render(definition);
    }

    private String render(PromptDefinition definition) {
        StringBuilder builder = new StringBuilder();
        for (PromptSegment segment : definition.getSegments()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(segment.getContent());
        }
        return builder.toString();
    }
}
