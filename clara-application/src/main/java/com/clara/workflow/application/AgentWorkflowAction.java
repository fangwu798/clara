package com.clara.workflow.application;

import com.clara.context.CancellationToken;
import com.clara.context.ConvContext;
import com.clara.core.workflow.IWorkflowAction;
import com.clara.dto.workflow.ExecutionScope;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.stream.IStreamObserver;
import com.clara.media.MediaAsset;
import com.clara.media.asset.MediaAssetIngestor;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;
import com.clara.orchestration.api.OrchestrationStatusEnum;
import com.clara.session.ModelBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AGENT 工作流动作：用提示词与变量调用模型，并把回复（或图片产物）作为输出。
 *
 * <p>是什么、在哪一层：application 工作流装配层对 core {@link IWorkflowAction} 的实现
 * （包 {@code com.clara.workflow.application}），由 {@link AgentActionFactory} 创建，供工作流引擎在
 * AGENT 节点执行。
 *
 * <p>执行流程：按节点选项（角色 / 模型别名）经 {@link AgentActionContext#modelRegistry()} 取到平台配置（未绑定
 * 则失败）→ 组装提示词与变量为 userInput（声明了 {@code attachments} 时以「文本 + 图片」多模态消息下发，
 * 用于图片识别）→ 经 {@link AgentActionContext#orchestrator()} 以节点声明的能力调用模型 → 按能力整理输出：
 * 文本能力把回复解析为 JSON 对象（失败则用原文），图片能力把产物引用经 {@link MediaAssetIngestor} 落成媒体
 * 资产；返回 {@code {"output": <值>}} 供节点落位。
 *
 * <p>失败语义：模型未绑定抛 {@link IllegalStateException}；节点声明的能力在该平台未配置时由
 * {@code ProviderConfiguration#getCapability} 抛配置异常；模型调用异常直接向上抛，由引擎把节点标记为失败。
 */
public final class AgentWorkflowAction implements IWorkflowAction {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 进度明细中「提示词 / 输入」预览的最大字符数；过短了看不出内容，过长了会在窄终端里折行错位。
     */
    private static final int PREVIEW_MAX_CHARS = 60;

    private final AgentActionContext context;
    private final String nodeId;
    private final String promptId;
    private final Map<String, Object> promptVars;
    private final AgentNodeOptions options;

    /**
     * 创建 AGENT 动作。
     *
     * @param context    协作依赖（编排器 / 提示词 / 模型注册表 / 媒体运行时），不可为 null
     * @param nodeId     节点 id，不可为空白
     * @param promptId   提示词标识，不可为空白
     * @param promptVars 提示词变量模板，可为 null（视为空）
     * @param options    节点选项（角色 / 模型别名 / 能力），不可为 null
     * @throws IllegalArgumentException 依赖或必填项非法时
     */
    public AgentWorkflowAction(AgentActionContext context, String nodeId, String promptId,
            Map<String, Object> promptVars, AgentNodeOptions options) {
        if (context == null || options == null) {
            throw new IllegalArgumentException("agent action dependencies must not be null");
        }
        if (blank(nodeId) || blank(promptId)) {
            throw new IllegalArgumentException("nodeId and promptId are required");
        }
        this.context = context;
        this.nodeId = nodeId;
        this.promptId = promptId;
        this.promptVars = promptVars == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(promptVars);
        this.options = options;
    }

    @Override
    public Map<String, Object> execute(ExecutionScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        ModelBinding binding = context.modelRegistry().resolve(options.role(), options.modelAlias());
        ProviderConfiguration configuration = binding.configuration();
        CapabilityKindEnum capability = options.capability();
        // 节点声明的能力在该平台未配置时立即失败（配置问题，不静默降级）
        configuration.getCapability(capability);
        String model = binding.model();
        if (blank(model)) {
            model = configuration.toConfig(capability).getModelId();
        }
        Object instanceValue = scope.get("instanceId");
        String instanceId = instanceValue == null ? nodeId : String.valueOf(instanceValue);
        Map<String, String> resolved = resolveVars(scope);
        String userInput = userInput(resolved);
        // 工作流节点调用是同步的；图片生成约 1 分钟，期间若无过程信息用户会以为卡住。这里先打一行进度，
        // 说明「哪个节点、调用什么能力、用哪个模型」，下一行给出本次实际输入（如精炼后的画面描述）。
        progress("节点 " + nodeId + "：调用" + capabilityLabel(capability) + "能力（model=" + model + "）");
        String preview = inputPreview(resolved);
        if (!preview.isEmpty()) {
            progressDetail("提示词：" + preview);
        }
        // 文本类节点复用当轮对话的流式观察者，实时显示思考 / 正文；图片 / 视频接口本身不产生思考、也不支持
        // 流式，故只保留前后进度行。
        IStreamObserver observer = WorkflowProgress.observer();
        boolean streaming = capability == CapabilityKindEnum.CHAT && observer != null;

        ConversationResult result = context.orchestrator().converse(ConversationRequest.builder()
                .conversationId(conversationId(instanceId))
                .provider(configuration.getProvider())
                .model(model)
                .capabilityKind(capability)
                .userInput(userInput)
                .attachments(attachments(scope))
                .providerConfiguration(configuration)
                .stream(streaming)
                .observer(streaming ? observer : null)
                .cancellationToken(currentCancellationToken())
                .build());

        if (result.getStatus() != OrchestrationStatusEnum.COMPLETED) {
            throw new IllegalStateException("工作流 AGENT 节点调用未成功（节点 " + nodeId + "，状态 "
                    + result.getStatus() + (result.getError() == null
                    ? "" : "，错误 " + result.getError().getSafeMessage()) + "）");
        }
        progress("节点 " + nodeId + "：完成");
        Object output = output(capability, result.getContent());
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("output", output);
        return response;
    }

    /**
     * 输出一行工作流节点进度，供用户在长耗时节点执行时观察进展。
     *
     * @param message 进度内容，非 null
     */
    private static void progress(String message) {
        WorkflowProgress.notice("[workflow] " + message);
    }

    /**
     * 能力的中文名，用于进度展示。
     */
    private static String capabilityLabel(CapabilityKindEnum capability) {
        if (capability == CapabilityKindEnum.IMAGE) {
            return "图片生成";
        }
        if (capability == CapabilityKindEnum.VIDEO) {
            return "视频生成";
        }
        return "文本";
    }

    /**
     * 节点输入摘要：优先展示节点声明的变量取值（如生图节点精炼后的画面描述），比整段内部提示词模板更贴近
     * 实际下发内容、也更短；无可展示取值时回退为提示词模板本身的摘要。
     */
    private String inputPreview(Map<String, String> resolved) {
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            if (blank(entry.getValue())) {
                continue;
            }
            parts.add(resolved.size() > 1 ? entry.getKey() + "=" + entry.getValue() : entry.getValue());
        }
        String text = parts.isEmpty() ? context.promptProvider().prompt(promptId) : String.join("；", parts);
        return summarize(text);
    }

    /**
     * 压缩空白并截断为单行摘要，用于进度明细行。
     */
    private static String summarize(String text) {
        if (blank(text)) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        if (single.isEmpty()) {
            return "";
        }
        return single.length() <= PREVIEW_MAX_CHARS
                ? single : single.substring(0, PREVIEW_MAX_CHARS) + "…";
    }

    /**
     * 输出进度明细行，缩进与 {@code [workflow] } 前缀对齐。
     */
    private static void progressDetail(String message) {
        WorkflowProgress.notice("           " + message);
    }

    /**
     * 按能力整理输出：图片能力把产物引用落成媒体资产，其余能力按文本 / JSON 处理。
     */
    private Object output(CapabilityKindEnum capability, String content) {
        if (capability == CapabilityKindEnum.IMAGE) {
            return ingestAssets(content);
        }
        Object output = parse(content);
        if (output == null || (output instanceof String && ((String) output).trim().isEmpty())) {
            throw new IllegalStateException("工作流 AGENT 节点回复为空（节点 " + nodeId + "）");
        }
        return output;
    }

    /**
     * 把图片生成结果（换行分隔的 URL / base64 引用）逐项落成媒体资产，返回资产描述列表。
     */
    private Object ingestAssets(String content) {
        if (blank(content)) {
            throw new IllegalStateException("工作流 AGENT 节点图片生成结果为空（节点 " + nodeId + "）");
        }
        MediaAssetIngestor ingestor = new MediaAssetIngestor(
                context.mediaRuntime().mediaStore(), context.mediaRuntime().tenantId());
        List<Map<String, Object>> assets = new ArrayList<Map<String, Object>>();
        for (String reference : content.split("\\r?\\n")) {
            if (blank(reference)) {
                continue;
            }
            assets.add(describe(ingestor.ingest(reference.trim())));
        }
        if (assets.isEmpty()) {
            throw new IllegalStateException("工作流 AGENT 节点未产出可用图片（节点 " + nodeId + "）");
        }
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("mediaType", "IMAGE");
        output.put("assets", assets);
        return output;
    }

    private static Map<String, Object> describe(MediaAsset asset) {
        Map<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put("assetId", asset.getAssetId());
        descriptor.put("storageUri", asset.getStorageUri());
        descriptor.put("mimeType", asset.getMimeType());
        descriptor.put("size", asset.getSize());
        descriptor.put("sha256", asset.getSha256());
        descriptor.put("sourceUri", asset.getSourceUri());
        return descriptor;
    }

    /**
     * 解析图片附件模板为实际引用（支持 {@code ${...}} 变量）；无附件时返回 null。
     */
    private List<String> attachments(ExecutionScope scope) {
        if (options.attachments().isEmpty()) {
            return null;
        }
        List<String> resolved = new ArrayList<String>();
        for (String template : options.attachments()) {
            String reference = WorkflowVariableResolver.stringify(
                    WorkflowVariableResolver.resolveValue(template, scope, nodeId));
            if (!blank(reference)) {
                resolved.add(reference.trim());
            }
        }
        return resolved.isEmpty() ? null : resolved;
    }

    /**
     * 解析节点声明的提示词变量为实际取值（键 → 字符串值）。
     */
    private Map<String, String> resolveVars(ExecutionScope scope) {
        Map<String, String> resolved = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : promptVars.entrySet()) {
            Object value = WorkflowVariableResolver.resolveValue(entry.getValue(), scope, nodeId);
            resolved.put(entry.getKey(), WorkflowVariableResolver.stringify(value));
        }
        return resolved;
    }

    /**
     * 组装节点输入：提示词模板文本 + 变量。
     *
     * <p>模板可用 {@code ${变量名}} 占位，命中时按占位位置替换（不再追加「## 名称」段）；未命中的变量仍以
     * 「## 名称」分段追加，兼容既有提示词。占位写法让上游产出（如精炼后的生图提示词）能原样作为节点输入。
     */
    private String userInput(Map<String, String> resolved) {
        String base = context.promptProvider().prompt(promptId);
        StringBuilder builder = new StringBuilder(base);
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            if (!base.contains("${" + entry.getKey() + "}")) {
                builder.append('\n').append("## ").append(entry.getKey()).append('\n')
                        .append(entry.getValue());
            }
        }
        String text = builder.toString();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            text = text.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return text;
    }

    /**
     * 组装会话标识：有角色时纳入角色，使同实例不同角色的窗口记忆互不污染。
     */
    private String conversationId(String instanceId) {
        return blank(options.role())
                ? "wf:" + instanceId + ":" + nodeId
                : "wf:" + instanceId + ":" + options.role() + ":" + nodeId;
    }

    /**
     * 尝试把模型回复解析为 JSON 对象（剥离代码围栏与首尾空白），失败则返回原始文本。
     */
    private static Object parse(String content) {
        if (content == null) {
            return null;
        }
        String cleaned = stripFence(content.trim());
        try {
            JsonNode node = JSON.readTree(cleaned);
            if (node != null && node.isObject()) {
                return JSON.convertValue(node, LinkedHashMap.class);
            }
        } catch (IOException ignored) {
            // 非 JSON 文本按原始文本回传
        }
        return content;
    }

    private static String stripFence(String text) {
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline >= 0) {
                text = text.substring(newline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 取当前线程所在会话的取消令牌，透传给嵌套的对话请求。
     *
     * <p>工作流由工具调用在同一线程上同步触发，外层会话已绑定，故能取到外层令牌；外部置位该令牌即可让本节点
     * 正在进行的模型调用随外层一起取消。线程未绑定会话时返回 null（请求退化为编排层自建令牌）。
     */
    private static CancellationToken currentCancellationToken() {
        ConvContext current = ConvContext.current();
        return current == null ? null : current.getCancellationToken();
    }
}
