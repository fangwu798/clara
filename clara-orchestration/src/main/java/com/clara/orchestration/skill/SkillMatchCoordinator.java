package com.clara.orchestration.skill;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.core.skill.IPromptProvider;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.clara.dto.tool.ToolContext;
import com.clara.dto.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能匹配协调器：用用户输入向量检索技能描述，选出本轮要放入的提示词与对应工具方法。
 *
 * <p>检索走 {@link ISemanticMemoryStore}（来源限定 {@code PROMPT} / {@code TOOL_DESCRIPTION}），
 * 提示词取回走 {@link IPromptProvider}；命中结果按相似度过滤、同技能去重后交由
 * {@code ContextAssembler} 注入（提示词并入 system，工具方法组装为请求的 tools 声明）。
 */
public final class SkillMatchCoordinator {

    private static final int MAX_TOOL_NAME_LENGTH = 128;

    private final IEmbeddingClient embeddingClient;
    private final ISemanticMemoryStore semanticMemoryStore;
    private final IPromptProvider promptProvider;

    /**
     * 创建技能匹配协调器。
     *
     * @param embeddingClient     向量化客户端，可为 null（此时不匹配）
     * @param semanticMemoryStore 语义记忆存储，可为 null（此时不匹配）
     * @param promptProvider      提示词提供方，可为 null（此时不取回提示词）
     */
    public SkillMatchCoordinator(IEmbeddingClient embeddingClient,
            ISemanticMemoryStore semanticMemoryStore, IPromptProvider promptProvider) {
        this.embeddingClient = embeddingClient;
        this.semanticMemoryStore = semanticMemoryStore;
        this.promptProvider = promptProvider;
    }

    /**
     * 按当前用户输入匹配技能提示词与工具方法。
     *
     * @param userInput 当前用户输入
     * @param control   上下文控制配置，不可为 null
     * @return 匹配结果，非 null（无命中时为空结果）
     */
    public MatchedSkill match(String userInput, ContextControlConfig control) {
        if (embeddingClient == null || semanticMemoryStore == null || control == null
                || control.getRetrievalCount() <= 0 || userInput == null || userInput.trim().isEmpty()) {
            return MatchedSkill.empty();
        }
        float[] vector = embeddingClient.embed(userInput);
        List<String> prompts = new ArrayList<String>();
        List<ToolContext> tools = new ArrayList<ToolContext>();
        for (MemoryMatch match : search(MemorySourceTypeEnum.PROMPT, vector, control)) {
            if (promptProvider != null && match.getEntry().getSourceId() != null) {
                prompts.add(promptProvider.prompt(match.getEntry().getSourceId()));
            }
        }
        for (MemoryMatch match : search(MemorySourceTypeEnum.TOOL_DESCRIPTION, vector, control)) {
            tools.add(new ToolContext(new ToolSpecification(
                    toolName(match.getEntry().getSourceId()),
                    match.getEntry().getContent(), emptySchema())));
        }
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

    private static Map<String, Object> emptySchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        return schema;
    }

    private static String toolName(String sourceId) {
        StringBuilder name = new StringBuilder();
        for (int index = 0; index < sourceId.length() && name.length() < MAX_TOOL_NAME_LENGTH; index++) {
            char value = sourceId.charAt(index);
            boolean allowed = (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9') || value == '_' || value == '-';
            name.append(allowed ? value : '_');
        }
        return name.length() == 0 ? "skill_tool" : name.toString();
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

        static MatchedSkill empty() {
            return new MatchedSkill(Collections.<String>emptyList(), Collections.<ToolContext>emptyList());
        }

        public List<String> getPrompts() {
            return prompts;
        }

        public List<ToolContext> getTools() {
            return tools;
        }
    }
}
