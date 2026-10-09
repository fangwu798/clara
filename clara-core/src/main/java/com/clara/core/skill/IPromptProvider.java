package com.clara.core.skill;

/**
 * 提示词接口：按标识取回提示词定义，是提示词资源的统一访问入口。
 *
 * <p>core 定义的契约接口，由 clara-skill 实现；总结提示词即通过本接口获取。
 */
public interface IPromptProvider {

    /**
     * 按提示词标识取回提示词定义文本。
     *
     * @param promptId 提示词标识，不可为空白
     * @return 提示词定义文本，非 null
     */
    String prompt(String promptId);

    /**
     * 取回上下文压缩总结用的提示词。
     *
     * @return 总结提示词文本，非 null
     */
    String summarizePrompt();
}
