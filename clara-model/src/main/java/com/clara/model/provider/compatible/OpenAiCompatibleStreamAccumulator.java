package com.clara.model.provider.compatible;

import com.clara.kernel.ClaraException;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.FinishReason;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import com.clara.model.api.TokenUsage;
import com.clara.model.api.ToolCallContent;
import com.clara.model.provider.compatible.config.OpenAiCompatibleChatConfig;
import com.clara.model.provider.compatible.config.OpenAiCompatibleReasoning;
import com.clara.model.provider.compatible.tool.StreamChunk;
import com.clara.model.provider.compatible.tool.ToolDelta;
import com.clara.model.provider.compatible.tool.ToolState;
import com.clara.model.utils.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 累积 OpenAI 兼容流式响应的增量，并在收到结束标记后生成完整结果。
 *
 * <p>用途：SSE 逐块到达时，把文本、推理内容与工具调用参数增量拼接起来，同时校验跨块字段的一致性
 * （{@code id}/{@code model}/{@code system_fingerprint} 不得变化、{@code usage} 不得前后矛盾、
 * {@code finish_reason} 不得重复且不一致），最终在 {@code [DONE]} 时产出 {@link ChatResponse}。
 *
 * <p>使用方式：一个实例只服务一次流，必须在读取线程上串行调用 {@link #accept(String)}；返回块的
 * {@link StreamChunk#isComplete()} 为 true 表示流已收尾（此时返回完整响应，供上层发
 * 完成事件），之后再次传入数据按协议错误拒绝；也可用 {@link #isComplete()} 回查是否已结束。
 *
 * <p>字段归属：推理内容取自兼容家族通用扩展字段 {@code reasoning_content}，不是某个平台私有，
 * 因此与 {@link OpenAiCompatibleChatCodec} 一样由本包统一解析，见 {@link OpenAiCompatibleReasoning}；
 * {@code finish_reason} 的映射同样与非流式共用，见 {@link OpenAiCompatibleFinishReasons}。
 *
 * <p>上限与错误：工具调用数量受 {@code maxToolCalls} 限制、累计字符数受 {@code maxAccumulatedChars}
 * 限制，超出抛 {@code MODEL_RESPONSE_TOO_LARGE}；流在 {@code [DONE]} 之前结束会因缺少
 * {@code finish_reason} 抛 {@code MODEL_PROTOCOL_ERROR}。异常统一由 {@link ExceptionUtils} 构造，
 * 本类不自行拼装错误码与错误类别。
 */
@Data
public class OpenAiCompatibleStreamAccumulator {
    /**
     * 对话配置，提供平台与协议档位，用于错误归因与响应元数据。
     */
    private final OpenAiCompatibleChatConfig config;
    /**
     * 允许的最大工具调用数量，超出按资源超限处理。
     */
    private final int maxToolCalls;
    /**
     * 文本、推理与工具参数合计的最大累计字符数。
     */
    private final int maxAccumulatedChars;
    /**
     * JSON 映射器，用于解析每块数据。
     */
    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * 已累积的文本内容。
     */
    private final StringBuilder text = new StringBuilder();
    /**
     * 已累积的推理内容。
     */
    private final StringBuilder reasoning = new StringBuilder();
    /**
     * 按序号累积的工具调用状态，使用有序 Map 保证输出顺序稳定。
     */
    private final Map<Integer, ToolState> tools = new TreeMap<>();
    /**
     * 响应 id，跨块出现时必须保持一致。
     */
    private String id;
    /**
     * 响应 model 字段，跨块出现时必须保持一致。
     */
    private String model;
    /**
     * system_fingerprint，跨块出现时必须保持一致。
     */
    private String fingerprint;
    /**
     * 结束原因，一旦确定不允许再变为不同值。
     */
    private FinishReason finishReason;
    /**
     * token 用量，跨块出现矛盾时按协议错误处理。
     */
    private TokenUsage usage;
    /**
     * 已累积字符数，用于累计上限判断。
     */
    private int accumulatedChars;
    /**
     * 是否出现过文本内容，决定最终响应是否包含文本块。
     */
    private boolean sawText;
    /**
     * 是否已收到结束标记并产出完整响应。
     */
    private boolean complete;

    /**
     * 创建累加器。
     *
     * @param config              对话配置，不可为 null
     * @param maxToolCalls        最大工具调用数量，必须为正
     * @param maxAccumulatedChars 最大累计字符数，必须为正
     * @throws IllegalArgumentException 配置为 null 或上限非正时
     */
    OpenAiCompatibleStreamAccumulator(OpenAiCompatibleChatConfig config, int maxToolCalls, int maxAccumulatedChars) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (maxToolCalls <= 0 || maxAccumulatedChars <= 0) {
            throw new IllegalArgumentException("stream limits must be positive");
        }
        this.config = config;
        this.maxToolCalls = maxToolCalls;
        this.maxAccumulatedChars = maxAccumulatedChars;
    }

    /**
     * 消费一块 SSE 数据，返回本次可推送的增量块。
     *
     * <p>数据为 {@code [DONE]} 时结束累积并返回收尾块；否则解析增量，按需拼接文本、推理与工具参数，
     * 并校验跨块字段的一致性。流未收尾时返回的块不携带完整响应。
     *
     * @param data 一块 SSE 数据（{@code data} 字段内容），不可为 null
     * @return 本次增量块；流收尾时返回携带完整响应的块
     * @throws IllegalArgumentException data 为 null 时
     * @throws ClaraException           流已收尾后继续传入数据、结构非法、跨块字段矛盾或超出上限时
     */
    StreamChunk accept(String data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (complete) {
            throw ExceptionUtils.protocolError();
        }
        if ("[DONE]".equals(data.trim())) {
            return complete();
        }
        try {
            JsonNode root = mapper.readTree(data);
            if (root == null || !root.isObject()) {
                throw ExceptionUtils.protocolError();
            }
            id = stable(id, JsonNodeUtils.strictTextOrNull(root.get("id")));
            model = stable(model, JsonNodeUtils.strictTextOrNull(root.get("model")));
            fingerprint = stable(fingerprint, JsonNodeUtils.strictTextOrNull(root.get("system_fingerprint")));
            TokenUsage chunkUsage = usage(root.get("usage"));
            if (chunkUsage != null) {
                usage = stableUsage(usage, chunkUsage);
            }
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() > 1) {
                throw ExceptionUtils.protocolError();
            }
            if (choices.isEmpty()) {
                if (chunkUsage == null) {
                    throw ExceptionUtils.protocolError();
                }
                return emptyChunk();
            }
            JsonNode choice = choices.get(0);
            JsonNode index = choice.get("index");
            if (index != null && (!index.canConvertToInt() || index.intValue() != 0)) {
                throw ExceptionUtils.protocolError();
            }
            JsonNode delta = choice.get("delta");
            if (delta == null || !delta.isObject()) {
                throw ExceptionUtils.protocolError();
            }
            String role = JsonNodeUtils.strictTextOrNull(delta.get("role"));
            if (role != null && !"assistant".equals(role)) {
                throw ExceptionUtils.protocolError();
            }
            String textDelta = JsonNodeUtils.strictTextOrNull(delta, "content");
            if (textDelta != null) {
                append(text, textDelta);
                sawText = true;
            }
            String reasoningDelta = JsonNodeUtils.strictTextOrNull(delta, OpenAiCompatibleReasoning.CONTENT_FIELD);
            if (reasoningDelta != null) {
                append(reasoning, reasoningDelta);
            }
            List<ToolDelta> toolDeltas = tools(delta.get("tool_calls"));
            String finish = JsonNodeUtils.strictTextOrNull(choice.get("finish_reason"));
            if (finish != null) {
                FinishReason mapped = FinishReasonUtils.of(finish);
                if (finishReason != null && finishReason != mapped) {
                    throw ExceptionUtils.protocolError();
                }
                finishReason = mapped;
            } else if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                throw ExceptionUtils.protocolError();
            }
            return new StreamChunk(textDelta, reasoningDelta, toolDeltas, null);
        } catch (ClaraException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw ExceptionUtils.protocolError();
        }
    }


    /**
     * 结束累积并生成完整响应。
     *
     * <p>要求此前出现过 {@code finish_reason}；把累积的文本与工具调用转换为响应内容，其中工具调用必须
     * 同时具备 id 与函数名，否则按协议错误处理；随后写入 id/model/system_fingerprint、推理内容与平台
     * 信息等元数据，并标记流已收尾。
     *
     * @return 携带完整响应的收尾块
     * @throws ClaraException 缺少结束原因、工具调用字段不完整或没有任何可返回内容时
     */
    private StreamChunk complete() {
        if (finishReason == null) {
            throw ExceptionUtils.protocolError();
        }
        List<Content> contents = new ArrayList<>();
        if (sawText) {
            contents.add(new TextContent(text.toString()));
        }
        for (ToolState tool : tools.values()) {
            if (StringUtils.isBlank(tool.getId().toString()) || StringUtils.isBlank(tool.getName().toString())) {
                throw ExceptionUtils.protocolError();
            }
            contents.add(new ToolCallContent(tool.getId().toString(), tool.getName().toString(), tool.getArguments().toString()));
        }
        if (contents.isEmpty()) {
            throw ExceptionUtils.protocolError();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        MapUtils.putIfNotNull(metadata, "id", id);
        MapUtils.putIfNotNull(metadata, "model", model);
        MapUtils.putIfNotNull(metadata, "systemFingerprint", fingerprint);
        if (reasoning.length() > 0) {
            metadata.put(OpenAiCompatibleReasoning.METADATA_KEY, reasoning.toString());
        }
        metadata.put("provider", config.getProvider().name());
        metadata.put("apiProfile", config.getApiProfile());
        ChatResponse response = new ChatResponse(new ChatMessage(Role.ASSISTANT, contents, null, null), usage, finishReason, metadata);
        complete = true;
        return new StreamChunk(null, null, Collections.emptyList(), response);
    }

    /**
     * 处理一块数据中的工具调用增量。
     *
     * <p>按 {@code index} 归并到同一工具状态，新建状态时校验工具数量上限；id、函数名与参数片段分别
     * 拼接，本块至少要包含其中之一，否则按协议错误处理。
     *
     * @param calls {@code tool_calls} 节点，可为 null
     * @return 本次的工具调用增量列表
     * @throws ClaraException 结构非法、本块无任何增量或工具数量超限时
     */
    private List<ToolDelta> tools(JsonNode calls) {
        if (calls == null || calls.isNull()) {
            return Collections.emptyList();
        }
        if (!calls.isArray()) {
            throw ExceptionUtils.protocolError();
        }
        List<ToolDelta> result = new ArrayList<>();
        for (JsonNode call : calls) {
            JsonNode index = call.get("index");
            if (index == null || !index.canConvertToInt() || index.intValue() < 0) {
                throw ExceptionUtils.protocolError();
            }
            int value = index.intValue();
            ToolState state = tools.get(value);
            if (state == null) {
                if (tools.size() >= maxToolCalls) {
                    throw ExceptionUtils.responseTooLarge();
                }
                state = new ToolState();
                tools.put(value, state);
            }
            String type = JsonNodeUtils.strictTextOrNull(call.get("type"));
            if (type != null && !"function".equals(type)) {
                throw ExceptionUtils.protocolError();
            }
            String idDelta = JsonNodeUtils.strictTextOrNull(call, "id");
            JsonNode function = call.get("function");
            if (function != null && !function.isNull() && !function.isObject()) {
                throw ExceptionUtils.protocolError();
            }
            String nameDelta = function == null || function.isNull() ? null : JsonNodeUtils.strictTextOrNull(function, "name");
            String argumentsDelta = function == null || function.isNull() ? null : JsonNodeUtils.strictTextOrNull(function, "arguments");
            if (idDelta == null && nameDelta == null && argumentsDelta == null) {
                throw ExceptionUtils.protocolError();
            }
            if (idDelta != null) {
                append(state.getId(), idDelta);
            }
            if (nameDelta != null) {
                append(state.getName(), nameDelta);
            }
            if (argumentsDelta != null) {
                append(state.getArguments(), argumentsDelta);
            }
            result.add(new ToolDelta(value, StringUtils.emptyToNull(state.getId()), StringUtils.emptyToNull(state.getName()), argumentsDelta));
        }
        return result;
    }

    /**
     * 追加增量内容并累计字符数。
     *
     * @param target 目标缓冲（文本、推理或工具字段）
     * @param value  本次增量
     * @throws ClaraException 累计字符数超出上限时
     */
    private void append(StringBuilder target, String value) {
        if (accumulatedChars > maxAccumulatedChars - value.length()) {
            throw ExceptionUtils.responseTooLarge();
        }
        target.append(value);
        accumulatedChars += value.length();
    }

    /**
     * 解析 token 用量。
     *
     * <p>必填 {@code prompt_tokens} 与 {@code completion_tokens}，缺失时按 0 处理；可选
     * {@code total_tokens} 与缓存命中量按「缺失即 null」读取，以便上层按输入加输出推导总量。
     *
     * @param value usage 节点，可为 null
     * @return token 用量，节点缺失时返回 null
     */
    private static TokenUsage usage(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return new TokenUsage(
                JsonNodeUtils.getIntValue(value, "prompt_tokens"),   //提示词输入token大小
                JsonNodeUtils.getIntValue(value, "completion_tokens"),//当前响应token大小
                JsonNodeUtils.optionalIntValue(value, "total_tokens"),//当前会话token总大小，缺失时为 null
                JsonNodeUtils.optionalIntValue(value, "cached_tokens") //当前请求命中缓存token大小，缺失时为 null
        );
    }


    /**
     * 校验同一流中多次出现的 token 用量保持一致。
     *
     * <p>输入、输出与缓存命中量必须完全一致；总量不比较，因为它可由前两者推导或按平台口径给出。
     *
     * @param current 已记录的用量，可为 null
     * @param next    本次出现的用量
     * @return 本次用量
     * @throws ClaraException 与已记录用量冲突时
     */
    private static TokenUsage stableUsage(TokenUsage current, TokenUsage next) {
        if (current != null && (current.getInputTokens() != next.getInputTokens() || current.getOutputTokens() != next.getOutputTokens() || !Objects.equals(current.getCacheTokens(), next.getCacheTokens()))) {
            throw ExceptionUtils.protocolError();
        }
        return next;
    }

    /**
     * 校验跨块字段保持一致。
     *
     * @param current 已记录的值，可为 null
     * @param next    本块出现的值，可为 null（表示本块未携带）
     * @return 校验后的值
     * @throws ClaraException 与已记录的值冲突时
     */
    private static String stable(String current, String next) {
        if (next == null) {
            return current;
        }
        if (current != null && !current.equals(next)) {
            throw ExceptionUtils.protocolError();
        }
        return next;
    }

    /**
     * 构造不含任何增量的空块。
     *
     * <p>用于只携带 {@code usage} 等元信息、没有内容增量的数据块。
     *
     * @return 空增量块
     */
    private static StreamChunk emptyChunk() {
        return new StreamChunk(null, null, Collections.emptyList(), null);
    }
}
