package com.clara.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 待人工确认工作流的会话级登记表。
 *
 * <p>为什么需要：{@code run_workflow} 遇到 HUMAN 节点会返回 {@code WAITING_INPUT}，实例标识只存在于
 * 上一次工具结果里。一旦发生模型故障转移、上下文压缩或历史净化，模型可能看不到该结果，于是把用户的
 * 「确认」误当成新任务，甚至去执行并不存在的 {@code clara.jar image}。本类把「哪个会话还有哪个实例
 * 停在哪个输入键」独立保存下来，下一轮由宿主注入为临时系统提示，不依赖模型自己从历史里翻找。
 *
 * <p>生命周期：由宿主进程持有，按会话标识登记；实例完成、失败或超过有效期后自动失效。
 * @see WorkflowRunTool
 */
public final class PendingWorkflowRegistry {

    /** 待确认状态的最长保留时间：超过后视为用户已放弃，不再提示模型续接。 */
    private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);

    /** 结构化结果解析器：只读取状态、实例标识与待填输入键。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 会话标识到待确认实例的映射；并发安全，供多会话同时使用。 */
    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * 根据一次工作流执行结果更新登记：暂停则记录，其余状态则清除。
     *
     * @param conversationId 会话标识，可为空白（此时忽略）
     * @param workflowId     工作流标识，可为空白
     * @param resultJson     运行器返回的结果 JSON，可为空白
     */
    public void update(String conversationId, String workflowId, String resultJson) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return;
        }
        if (resultJson == null || resultJson.trim().isEmpty()) {
            pending.remove(conversationId);
            return;
        }
        try {
            JsonNode root = JSON.readTree(resultJson);
            String status = root.path("status").asText("");
            String instanceId = root.path("instanceId").asText("");
            if (!"WAITING_INPUT".equals(status) || instanceId.trim().isEmpty()) {
                pending.remove(conversationId);
                return;
            }
            String inputKey = root.path("waitingInputKey").asText("");
            if (inputKey.trim().isEmpty()) {
                inputKey = "promptFeedback";
            }
            String prompt = root.path("outputs").path("prompt").asText("");
            pending.put(conversationId, new Pending(workflowId, instanceId.trim(), inputKey.trim(),
                    prompt, System.currentTimeMillis() + TTL_MILLIS));
        } catch (IOException ignored) {
            // 结果不是 JSON 时不改动登记：宁可保留旧状态，也不要因解析失败丢掉待确认实例。
        }
    }

    /**
     * 取当前会话的续接提示，供宿主注入为本轮临时系统提示。
     *
     * @param conversationId 会话标识，可为空白
     * @return 提示文本；没有待确认实例或已过期时返回 null
     */
    public String hint(String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return null;
        }
        Pending current = pending.get(conversationId);
        long now = System.currentTimeMillis();
        if (current == null) {
            return null;
        }
        if (current.expiresAt < now) {
            pending.remove(conversationId, current);
            return null;
        }
        StringBuilder hint = new StringBuilder();
        hint.append("【待人工确认的工作流】\n")
                .append("上一轮 run_workflow 已暂停：workflow=").append(current.workflowId)
                .append("，instanceId=").append(current.instanceId)
                .append("，待填输入键=").append(current.inputKey).append("。\n");
        if (current.prompt != null && !current.prompt.trim().isEmpty()) {
            String prompt = current.prompt.trim();
            hint.append("待确认内容：")
                    .append(prompt.length() > 300 ? prompt.substring(0, 300) + "…" : prompt)
                    .append("\n");
        }
        hint.append("若用户本轮是在确认或修改上一轮内容：必须调用 run_workflow，参数为 ")
                .append("{\"workflow\":\"").append(current.workflowId)
                .append("\",\"instanceId\":\"").append(current.instanceId)
                .append("\",\"inputs\":{\"").append(current.inputKey)
                .append("\":\"approve\"}}；确认用 approve，修改意见用用户原文替换 approve。\n")
                .append("不要调用 run_command 执行 java -jar clara.jar image / video（不存在该子命令），")
                .append("也不要把确认当成新任务重新开始。\n")
                .append("若用户本轮明确提出了新任务，忽略本提示，不要续接该 instanceId。");
        return hint.toString();
    }

    /**
     * 清除指定会话的待确认登记。
     *
     * @param conversationId 会话标识，可为空白（此时忽略）
     */
    public void clear(String conversationId) {
        if (conversationId != null && !conversationId.trim().isEmpty()) {
            pending.remove(conversationId);
        }
    }

    /**
     * 一条待确认记录：工作流标识、实例标识、待填输入键、内容预览与过期时间。
     */
    private static final class Pending {

        /** 工作流标识。 */
        private final String workflowId;
        /** 暂停实例标识。 */
        private final String instanceId;
        /** 待填输入键（如 promptFeedback）。 */
        private final String inputKey;
        /** 待确认内容预览（如精炼后的生图提示词）。 */
        private final String prompt;
        /** 过期时间戳（毫秒）。 */
        private final long expiresAt;

        private Pending(String workflowId, String instanceId, String inputKey, String prompt, long expiresAt) {
            this.workflowId = workflowId;
            this.instanceId = instanceId;
            this.inputKey = inputKey;
            this.prompt = prompt;
            this.expiresAt = expiresAt;
        }
    }
}
