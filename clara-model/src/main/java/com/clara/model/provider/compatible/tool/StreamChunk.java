package com.clara.model.provider.compatible.tool;

import com.clara.model.api.ChatResponse;
import com.clara.model.provider.compatible.OpenAiCompatibleStreamAccumulator;
import com.clara.model.provider.compatible.OpenAiCompatibleStreamingChatModel;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 表示一次 SSE 解析产出的增量块，是从流到 {@link ChatResponse} 的中间载体。
 *
 * <p>用途：由 {@link OpenAiCompatibleStreamAccumulator#accept(String)} 返回给
 * {@link OpenAiCompatibleStreamingChatModel}，用于决定本次要推送哪些流事件：
 * <ul>
 *   <li>普通块：只带本次新增的 {@code textDelta}、{@code reasoningDelta} 与 {@code toolDeltas}，
 *       此时 {@link #getResponse()} 为 null；</li>
 *   <li>收尾块：{@link #isComplete()} 为 true，增量字段为空，{@link #getResponse()} 携带累积完成的
 *       完整响应，上层据此发完成事件。</li>
 * </ul>
 *
 * <p>注意：增量字段可能为 null（该块没有对应内容），使用前需判空；{@code toolDeltas} 始终非 null，
 * 但可能为空列表；除收尾块外不会携带完整响应。
 */
@Data
public final class StreamChunk {
    /**
     * 本次新增的文本内容，无新增时为 null。
     */
    private final String textDelta;
    /**
     * 本次新增的推理内容（兼容家族通用扩展字段 {@code reasoning_content}），无新增时为 null。
     */
    private final String reasoningDelta;
    /**
     * 本次新增的工具调用参数增量，非 null，可能为空列表。
     */
    private final List<ToolDelta> toolDeltas;
    /**
     * 完整响应；仅在流收尾的块上非 null。
     */
    private final ChatResponse response;

    /**
     * 创建增量块。
     *
     * @param textDelta      文本增量，可为 null
     * @param reasoningDelta 推理增量，可为 null
     * @param toolDeltas     工具调用增量，可为 null（按空列表处理）
     * @param response       完整响应，仅收尾块非 null
     */
     public StreamChunk(String textDelta, String reasoningDelta, List<ToolDelta> toolDeltas, ChatResponse response) {
        this.textDelta = textDelta;
        this.reasoningDelta = reasoningDelta;
        this.toolDeltas = toolDeltas == null ? Collections.<ToolDelta>emptyList() : Collections.unmodifiableList(new ArrayList<>(toolDeltas));
        this.response = response;
    }

    /**
     * 判断是否为收尾块。
     *
     * @return 携带完整响应时返回 true
     */
    public boolean isComplete() {
        return response != null;
    }
}
