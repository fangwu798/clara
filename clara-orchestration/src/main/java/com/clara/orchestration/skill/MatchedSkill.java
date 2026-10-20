package com.clara.orchestration.skill;

import com.clara.dto.tool.ToolContext;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 技能匹配结果：本轮要注入的提示词与工具声明。
 *
 * <p>实例不可变：构造时对提示词和工具列表做防御性拷贝，并通过只读视图对外暴露。
 */
@Data
public final class MatchedSkill {

    /** 本轮命中的技能提示词，按相似度从高到低排列。 */
    private final List<String> prompts;

    /** 本轮可用工具声明。 */
    private final List<ToolContext> tools;

    /**
     * 创建技能匹配结果。
     *
     * @param prompts 本轮命中的技能提示词
     * @param tools   本轮可用工具声明
     */
    MatchedSkill(List<String> prompts, List<ToolContext> tools) {
        this.prompts = Collections.unmodifiableList(new ArrayList<>(prompts));
        this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
    }
}