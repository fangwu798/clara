package com.clara.workflow.runtime;

import com.clara.core.workflow.IWorkflowEngine;
import com.clara.dto.workflow.ExecutionScope;
import com.clara.dto.workflow.WorkflowDefinition;
import com.clara.dto.workflow.WorkflowResult;
import com.clara.enums.workflow.WorkflowInstanceStatusEnum;
import com.clara.workflow.definition.WorkflowDefinitionRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流运行器：按标识启动一个工作流实例，并把执行结果整理为 JSON 文本。
 *
 * <p>是什么、在哪一层：clara-workflow 的对外入口（包 {@code com.clara.workflow.runtime}），
 * 供宿主的内置工具（如 run_workflow）调用；它组合定义注册表与工作流引擎，屏蔽执行细节。
 *
 * <p>执行流程：先按需刷新定义注册表（运行期新增的自定义工作流即时可用）→ 按标识取定义 → 生成实例 id →
 * 解析输入（空为 {}）并写入 {@code instanceId} → 启动引擎 →
 * 汇总状态、等待信息与输出为 JSON 返回。若工作流通过顶层 {@code outputs} 声明了对外输出键，则只回传这些
 * 变量（避免把批量读取的源码等中间产物整体回灌给模型）；输出中超过 20000 字符的字符串会被截断。
 *
 * <p>失败语义：标识不存在或输入非法抛 {@link IllegalArgumentException}；工作流执行失败按引擎约定向上抛。
 */
public final class WorkflowRunner {

    private static final String FIELD_INSTANCE_ID = "instanceId";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> INPUTS_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>() { };
    private static final int MAX_TEXT_CHARS = 20000;
    private static final String TRUNCATED_SUFFIX = "…(truncated)";

    private final IWorkflowEngine engine;
    private final WorkflowDefinitionRegistry registry;

    /**
     * 创建工作流运行器。
     *
     * @param engine   工作流引擎，不可为 null
     * @param registry 工作流定义注册表，不可为 null
     * @throws IllegalArgumentException 任一项为 null 时
     */
    public WorkflowRunner(IWorkflowEngine engine, WorkflowDefinitionRegistry registry) {
        if (engine == null || registry == null) {
            throw new IllegalArgumentException("engine and registry must not be null");
        }
        this.engine = engine;
        this.registry = registry;
    }

    /**
     * 运行指定工作流并返回结果 JSON 文本。
     *
     * @param workflowId 工作流标识，不可为空白
     * @param inputsJson 输入变量 JSON 对象文本，可为 null / 空白（视为空）
     * @return 结果 JSON 文本，非 null
     * @throws IllegalArgumentException 标识不存在或输入非法时
     */
    public String run(String workflowId, String inputsJson) {
        registry.refresh();
        WorkflowDefinition definition = require(workflowId);
        String instanceId = workflowId + "-" + System.currentTimeMillis();
        LinkedHashMap<String, Object> inputs = parseInputs(inputsJson);
        if (!inputs.containsKey(FIELD_INSTANCE_ID)) {
            inputs.put(FIELD_INSTANCE_ID, instanceId);
        }
        WorkflowResult result = engine.start(definition, instanceId, inputs);
        return payload(workflowId, instanceId, result);
    }

    /**
     * 恢复一个正在等待人工输入的工作流实例，并返回结果 JSON 文本。
     *
     * <p>使用场景：{@code run} 返回 {@code WAITING_INPUT} 后，把提示词展示给用户；用户批准或提出修改意见，
     * 再调用本方法并传入同一实例 id 与对应输入键的值（如 {@code promptFeedback=approve}）。
     *
     * @param workflowId 工作流标识，不可为空白
     * @param instanceId 首次运行返回的实例标识，不可为空白
     * @param inputsJson 人工输入 JSON 对象文本，可为 null / 空白（视为空）
     * @return 结果 JSON 文本，非 null
     * @throws IllegalArgumentException 标识不存在、实例标识为空或输入非法时
     */
    public String resume(String workflowId, String instanceId, String inputsJson) {
        if (instanceId == null || instanceId.trim().isEmpty()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        registry.refresh();
        WorkflowDefinition definition = require(workflowId);
        LinkedHashMap<String, Object> inputs = parseInputs(inputsJson);
        // 实例标识由首次运行确定，恢复时不允许被输入覆盖。
        inputs.remove(FIELD_INSTANCE_ID);
        WorkflowResult result = engine.resume(definition, instanceId.trim(), inputs);
        return payload(workflowId, instanceId.trim(), result);
    }

    /**
     * 把执行结果整理为对模型友好的 JSON：状态、等待信息与声明的输出变量。
     *
     * @param workflowId 工作流标识，非空白
     * @param instanceId 实例标识，非空白
     * @param result     引擎结果，非 null
     * @return 结果 JSON 文本，非 null
     */
    private String payload(String workflowId, String instanceId, WorkflowResult result) {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workflowId", workflowId);
        payload.put(FIELD_INSTANCE_ID, instanceId);
        payload.put("status", result.getStatus() == null ? null : result.getStatus().name());
        payload.put("waitingNodeId", result.getWaitingNodeId());
        payload.put("waitingInputKey", result.getWaitingInputKey());

        List<String> declaredOutputs = registry.outputs(workflowId);
        Map<String, Object> outputs = new LinkedHashMap<>();
        ExecutionScope scope = result.getScope();
        for (Map.Entry<String, Object> entry : scope.getVariables().entrySet()) {
            boolean instanceField = FIELD_INSTANCE_ID.equals(entry.getKey());
            boolean declared = declaredOutputs.isEmpty() || declaredOutputs.contains(entry.getKey());
            if (!instanceField && declared) {
                outputs.put(entry.getKey(), sanitize(entry.getValue()));
            }
        }
        payload.put("outputs", outputs);
        boolean waitingForUser = result.getStatus() == WorkflowInstanceStatusEnum.WAITING_INPUT;
        payload.put("waitingForUser", waitingForUser);
        if (waitingForUser) {
            payload.put("display", waitingDisplay(outputs));
            payload.put("nextAction", nextAction(workflowId, instanceId, result.getWaitingInputKey()));
        }
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工作流结果序列化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 组装「如何续接」的结构化指令：模型下一轮必须按此调用 run_workflow，而不是把确认当成新任务，
     * 也不是去执行 {@code java -jar clara.jar image}（该子命令不存在）。
     *
     * @param workflowId 工作流标识，非空白
     * @param instanceId 暂停实例标识，非空白
     * @param inputKey   待填输入键，为 null 时按 promptFeedback 处理
     * @return 续接指令，非 null
     */
    private static Map<String, Object> nextAction(String workflowId, String instanceId, String inputKey) {
        String key = inputKey == null || inputKey.trim().isEmpty() ? "promptFeedback" : inputKey.trim();
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(key, "approve");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("workflow", workflowId);
        arguments.put("instanceId", instanceId);
        arguments.put("inputs", inputs);
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("tool", "run_workflow");
        action.put("arguments", arguments);
        action.put("rule", "用户确认时按此参数续接；若用户提出修改意见，把 approve 换成用户原文。"
                + "不要执行 clara.jar image / video（没有该子命令），也不要把确认当成新任务重新开始。");
        return action;
    }

    /**
     * 生成暂停时的用户可见文案：列出已产出的对外变量，并提示确认或修改。
     *
     * @param outputs 已声明输出，非 null
     * @return 展示文本，非 null
     */
    private static String waitingDisplay(Map<String, Object> outputs) {
        StringBuilder text = new StringBuilder("工作流已暂停，等待人工确认。");
        if (!outputs.isEmpty()) {
            text.append('\n');
            for (Map.Entry<String, Object> entry : outputs.entrySet()) {
                text.append(entry.getKey()).append("：").append(entry.getValue()).append('\n');
            }
        }
        return text.append("请回复“确认”继续，或直接提出修改意见。").toString();
    }

    /**
     * 按标识取定义；标识不存在而注册表又恰好有解析失败的定义文件时，把失败原因一并抛出，
     * 让调用方（模型）能直接看到「自定义工作流写错了哪里」而不是只得到一句「工作流不存在」。
     */
    private WorkflowDefinition require(String workflowId) {
        try {
            return registry.require(workflowId);
        } catch (IllegalArgumentException e) {
            String failure = registry.lastError();
            if (failure == null) {
                throw e;
            }
            throw new IllegalArgumentException(
                    e.getMessage() + "；另有工作流定义加载失败：" + failure + "（修正该文件后重试即可自动生效）", e);
        }
    }

    private static LinkedHashMap<String, Object> parseInputs(String inputsJson) {
        if (inputsJson == null || inputsJson.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            LinkedHashMap<String, Object> inputs = JSON.readValue(inputsJson, INPUTS_TYPE);
            return inputs == null ? new LinkedHashMap<>() : inputs;
        } catch (IOException e) {
            throw new IllegalArgumentException("工作流输入不是合法 JSON 对象：" + e.getMessage(), e);
        }
    }

    private static Object sanitize(Object value) {
        if (value instanceof String) {
            String text = (String) value;
            return text.length() > MAX_TEXT_CHARS
                    ? text.substring(0, MAX_TEXT_CHARS) + TRUNCATED_SUFFIX
                    : text;
        }
        if (value == null || value instanceof Number || value instanceof Boolean
                || value instanceof Map || value instanceof List) {
            return value;
        }
        return String.valueOf(value);
    }
}
