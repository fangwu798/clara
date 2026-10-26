package com.clara.orchestration.intent;

import com.clara.core.memory.IContextTraceSink;
import com.clara.core.message.IContent;
import com.clara.core.model.IStreamModel;
import com.clara.core.skill.IToolInvoker;
import com.clara.dto.planner.ExecutionPlan;
import com.clara.dto.planner.ExecutionStep;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.dto.tool.ToolResult;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.utils.StringUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 意图识别与动态计划协调器：先判断本轮输入是简单任务还是复杂任务，复杂任务再生成并通过工具校验执行计划。
 *
 * <p>为什么需要：简单问答不必背上计划的开销；复杂任务先形成可校验的步骤清单，再按计划调用工具，
 * 可以减少模型「想到哪做到哪」造成的漏步与重复。计划只在当前轮作为 system 指令注入，不写入对话历史。
 *
 * <p>失败语义：意图识别与计划生成都是增强能力——模型未返回、JSON 不合法、校验不通过或工具不可用时，
 * 一律退回「简单任务」由主链路直接回答，不阻断本轮对话。
 */
public final class IntentPlanCoordinator {

    /** 观测源：意图判断。 */
    public static final String SOURCE_INTENT = "intent";

    /** 观测源：执行计划。 */
    public static final String SOURCE_PLAN = "plan";

    /** 观测源：计划格式校验（工具校验 / 本地兜底校验）。 */
    public static final String SOURCE_VALIDATION = "validation";

    /** 计划校验工具名，与 {@code config/skill/tools/builtin/validate_execution_plan.json} 一致。 */
    public static final String VALIDATE_PLAN_TOOL = "validate_execution_plan";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 明显不需要计划的短输入：直接跳过模型判断，省一次调用。 */
    private static final Pattern TRIVIAL_INPUT = Pattern.compile(
            "^(你好|您好|hi|hello|hey|谢谢|thanks|thank\\s+you|好的|好|嗯|在吗|再见|bye)"
                    + "[\\s!！?？.。~]*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final String ROUTER_PROMPT = String.join("\n",
            "你是 Clara 的任务复杂度路由器。先判断用户本轮请求属于简单任务还是复杂任务，再决定是否生成执行计划。",
            "判定只看本轮 user 消息：不要携带、引用或猜测任何历史对话；未提供的历史一律视为不存在。",
            "",
            "复杂任务（需要计划）：需要两个以上步骤、跨多个文件或工具、先分析再修改再验证，",
            "或用户明确要求方案 / 计划 / 重构 / 实现 / 迁移 / 排查。",
            "若用户要求多角色 / 多模型 / 评审团 / 多方讨论 / 交叉评审 / 共同决议，必须判为复杂任务，",
            "并在计划第一步写明调用 run_workflow（review_panel 或 decision_panel）；禁止自行模拟多个角色。",
            "简单任务（不需要计划）：闲聊、问候、单点事实问答、单个操作或一条命令即可完成；",
            "宁可判简单，不要为了使用计划而把简单问题复杂化。",
            "",
            "只输出严格 JSON，不要输出 Markdown 代码围栏或任何解释。",
            "简单任务：{\"mode\":\"simple\",\"reason\":\"一句话判断依据\"}",
            "复杂任务：{\"mode\":\"complex\",\"reason\":\"一句话判断依据\",\"goal\":\"本轮要达成的目标\",",
            "\"steps\":[{\"id\":\"s1\",\"title\":\"步骤标题\",\"description\":\"具体做什么\",",
            "\"dependencies\":[],\"expectedOutput\":\"预期产出\"}]}",
            "",
            "约束：",
            "1. steps 至少 1 步，最多 %d 步；id 唯一；dependencies 只能引用已存在的 id；不得形成环。",
            "2. 步骤要具体可执行，能对应到读取、修改、运行、验证等动作。",
            "3. 只输出 JSON 对象，不要输出其他文字。");

    private final IStreamModel model;
    private final IToolInvoker toolInvoker;
    private final ExecutionPlanValidator validator;
    private final int maxRepairAttempts;
    private final IContextTraceSink traceSink;

    /**
     * 创建意图与计划协调器。
     *
     * @param model              模型调用入口，不可为 null
     * @param toolInvoker        计划校验工具的执行入口，可为 null（此时退回本地校验）
     * @param validator          计划校验器，不可为 null
     * @param maxRepairAttempts  校验失败后的最大修正次数，不得为负
     * @param traceSink          观测口，可为 null
     * @throws IllegalArgumentException model / validator 为 null，或修正次数为负时
     */
    public IntentPlanCoordinator(IStreamModel model, IToolInvoker toolInvoker, ExecutionPlanValidator validator, int maxRepairAttempts, IContextTraceSink traceSink) {
        if (model == null || validator == null) {
            throw new IllegalArgumentException("model and validator must not be null");
        }
        if (maxRepairAttempts < 0) {
            throw new IllegalArgumentException("maxRepairAttempts must not be negative");
        }
        this.model = model;
        this.toolInvoker = toolInvoker;
        this.validator = validator;
        this.maxRepairAttempts = maxRepairAttempts;
        this.traceSink = traceSink;
    }

    /**
     * 判断意图并为复杂任务生成通过校验的执行计划。
     *
     * @param userInput 本轮用户输入
     * @param modelId   当前模型标识，可为 null（由平台能力配置决定）
     * @return 意图判断结果，非 null；任何异常都退化为简单任务
     */
    public PreparedIntent prepare(String userInput, String modelId) {
        if (StringUtils.isBlank(userInput) || TRIVIAL_INPUT.matcher(userInput.trim()).matches()) {
            trace(SOURCE_INTENT, "简单任务", true, 0L, Collections.singletonList("问候/致谢等短输入，跳过模型判断"));
            return PreparedIntent.simple("无需计划");
        }
        long intentStarted = System.nanoTime();
        trace(SOURCE_INTENT, "判断中", true, 0L, Collections.singletonList("调用 " + orDefault(modelId, "默认模型") + " 判断：simple / complex + 计划"));
        int repairs = 0;
        try {
            IntentDecision decision = requestDecision(userInput, modelId, null, null);
            while (decision.node == null && repairs < maxRepairAttempts) {
                trace(SOURCE_INTENT, "重新判断", true, elapsed(intentStarted), Collections.singletonList("第 " + (repairs + 1) + " 次修正：" + decision.error));
                decision = requestDecision(userInput, modelId, decision.raw, decision.error);
                repairs++;
            }
            if (decision.node == null) {
                return fallback(SOURCE_INTENT, "意图判断失败：" + decision.error, intentStarted);
            }
            String reason = text(decision.node, "reason");
            if (!isComplex(decision.node)) {
                trace(SOURCE_INTENT, "简单任务", true, elapsed(intentStarted), Collections.singletonList("依据：" + orDefault(reason, "直接回答") + "，不生成计划"));
                return PreparedIntent.simple(reason);
            }
            trace(SOURCE_INTENT, "复杂任务", true, elapsed(intentStarted), Collections.singletonList("依据：" + orDefault(reason, "需要多步骤执行") + "，开始生成执行计划"));
            long planStarted = System.nanoTime();
            JsonNode planNode = planNode(decision.node);
            trace(SOURCE_PLAN, "生成完成", true, elapsed(intentStarted), planSnapshot(planNode));
            PlanValidation validation = validate(planNode);
            while (!validation.ok && repairs < maxRepairAttempts) {
                trace(SOURCE_PLAN, "修正中", false, elapsed(planStarted), Collections.singletonList("第 " + (repairs + 1) + " 次修正：" + validation.error));
                decision = requestDecision(userInput, modelId, decision.raw, validation.error);
                repairs++;
                if (decision.node == null) {
                    validation = PlanValidation.fail(decision.error);
                    continue;
                }
                if (!isComplex(decision.node)) {
                    trace(SOURCE_INTENT, "简单任务", true, elapsed(intentStarted), Collections.singletonList("修正后判定无需计划"));
                    return PreparedIntent.simple(text(decision.node, "reason"));
                }
                planNode = planNode(decision.node);
                trace(SOURCE_PLAN, "修正后已生成", true, elapsed(planStarted), planSnapshot(planNode));
                validation = validate(planNode);
            }
            if (!validation.ok) {
                return fallback(SOURCE_PLAN, "执行计划校验未通过：" + validation.error, planStarted);
            }
            trace(SOURCE_PLAN, "已生成", true, elapsed(intentStarted), stepLines(validation.plan));
            return PreparedIntent.complex(validation.plan, reason);
        } catch (RuntimeException failure) {
            return fallback(SOURCE_INTENT, "意图识别异常：" + message(failure), intentStarted);
        }
    }

    private IntentDecision requestDecision(String userInput, String modelId, String previousRaw, String repairError) {
        List<IContent> messages = new ArrayList<>();
        messages.add(new SystemContent(String.format(Locale.ROOT, ROUTER_PROMPT, validator.getMaxSteps())));
        messages.add(new UserContent(userInput));
        if (repairError != null) {
            if (!StringUtils.isBlank(previousRaw)) {
                messages.add(new AssistantContent(previousRaw));
            }
            messages.add(new UserContent("上一次输出未通过检查：" + repairError + "\n请只输出修正后的严格 JSON（格式与最初要求一致），不要解释，不要代码围栏。"));
        }
        HttpRequest request = HttpRequest.builder()
                .model(modelId)
                .messages(messages)
                .stream(Boolean.FALSE)
                .build();
        HttpResponse response = model.chat(request, HttpResponse.class);
        String raw = response == null ? null : response.getContent();
        if (StringUtils.isBlank(raw)) {
            return new IntentDecision(null, raw, "模型未返回内容");
        }
        try {
            return new IntentDecision(JSON.readTree(jsonCandidate(raw)), raw, null);
        } catch (JsonProcessingException failure) {
            return new IntentDecision(null, raw, "输出不是合法 JSON：" + failure.getOriginalMessage());
        }
    }

    private PlanValidation validate(JsonNode planNode) {
        if (planNode == null) {
            trace(SOURCE_VALIDATION, "未通过", false, 0L, Collections.singletonList("原因：缺少计划对象"));
            return PlanValidation.fail("缺少计划对象");
        }
        long started = System.nanoTime();
        if (toolInvoker != null) {
            trace(SOURCE_VALIDATION, "校验中", true, 0L, Collections.singletonList("调用工具 " + VALIDATE_PLAN_TOOL));
            try {
                ToolResult result = toolInvoker.invoke(validationCall(planNode));
                JsonNode output = result == null || StringUtils.isBlank(result.getOutput()) ? null : JSON.readTree(result.getOutput());
                if (output != null && output.path("ok").asBoolean(false)) {
                    PlanValidation passed = PlanValidation.passed(validator.validate(planNode));
                    trace(SOURCE_VALIDATION, "通过", true, elapsed(started), validationDetails(passed));
                    return passed;
                }
                String error = output == null ? "计划校验工具未返回结果" : output.path("error").asText("计划格式校验未通过");
                trace(SOURCE_VALIDATION, "未通过", false, elapsed(started), Collections.singletonList("原因：" + error));
                return PlanValidation.fail(error);
            } catch (IOException | RuntimeException failure) {
                // 工具不可用或入参异常时退回本地校验，保证计划能力不依赖宿主是否正确注册工具。
                trace(SOURCE_VALIDATION, "工具不可用", false, elapsed(started), Collections.singletonList("改用本地校验：" + message(failure)));
            }
        }
        try {
            PlanValidation passed = PlanValidation.passed(validator.validate(planNode));
            trace(SOURCE_VALIDATION, "通过（本地）", true, elapsed(started), validationDetails(passed));
            return passed;
        } catch (RuntimeException failure) {
            String error = message(failure);
            trace(SOURCE_VALIDATION, "未通过（本地）", false, elapsed(started), Collections.singletonList("原因：" + error));
            return PlanValidation.fail(error);
        }
    }

    private static ToolCall validationCall(JsonNode planNode) {
        ToolCallFunction function = new ToolCallFunction();
        function.setName(VALIDATE_PLAN_TOOL);
        function.setArguments("{\"plan\":" + planNode + "}");
        ToolCall call = new ToolCall();
        call.setId("intent-plan-validation");
        call.setType("function");
        call.setFunction(function);
        return call;
    }

    private static boolean isComplex(JsonNode node) {
        String mode = text(node, "mode");
        if ("simple".equalsIgnoreCase(mode)) {
            return false;
        }
        if ("complex".equalsIgnoreCase(mode)) {
            return true;
        }
        JsonNode nested = node.get("plan");
        if (nested != null && nested.isObject()) {
            return true;
        }
        JsonNode steps = node.get("steps");
        return steps != null && steps.isArray() && !steps.isEmpty();
    }

    private static JsonNode planNode(JsonNode decision) {
        JsonNode nested = decision.get("plan");
        return nested != null && nested.isObject() ? nested : decision;
    }

    private static String jsonCandidate(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int bodyStart = text.indexOf('\n', fence);
            int bodyEnd = bodyStart < 0 ? -1 : text.indexOf("```", bodyStart);
            if (bodyStart >= 0 && bodyEnd > bodyStart) {
                text = text.substring(bodyStart + 1, bodyEnd).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !StringUtils.isBlank(value.asText())
                ? value.asText().trim() : null;
    }

    private static String orDefault(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value;
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return StringUtils.isBlank(message) ? failure.getClass().getSimpleName() : message;
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static List<String> stepLines(ExecutionPlan plan) {
        List<String> lines = new ArrayList<>();
        for (ExecutionStep step : plan.getSteps()) {
            lines.add("[" + step.getId() + "] " + step.getTitle());
        }
        return lines;
    }

    /**
     * 渲染原始计划快照：校验前也能看到模型给了什么，便于判断是哪一步没通过。
     *
     * @param planNode 计划 JSON 节点，可为 null
     * @return 明细行，非 null
     */
    private static List<String> planSnapshot(JsonNode planNode) {
        JsonNode steps = planNode == null ? null : planNode.get("steps");
        int count = steps != null && steps.isArray() ? steps.size() : 0;
        List<String> details = new ArrayList<>(2);
        details.add("目标：" + orDefault(text(planNode, "goal"), "（未提供）"));
        details.add("步骤：" + count + " 条");
        return details;
    }

    /**
     * 渲染校验通过摘要。
     *
     * @param validation 校验结果，不可为 null 且必须通过
     * @return 明细行，非 null
     */
    private static List<String> validationDetails(PlanValidation validation) {
        return Collections.singletonList("步骤 " + validation.plan.getSteps().size() + " 条，结构 / 依赖 / 无环检查通过");
    }

    private PreparedIntent fallback(String source, String reason, long startedNanos) {
        trace(source, "已降级", false, elapsed(startedNanos), Collections.singletonList("改为直接回答：" + reason));
        return PreparedIntent.simple(reason);
    }

    private void trace(String source, String status, boolean success, long elapsedMillis, List<String> details) {
        if (traceSink == null) {
            return;
        }
        try {
            traceSink.onTrace(source, status, success, elapsedMillis, details);
        } catch (RuntimeException ignored) {
            // 观测是旁路：渲染失败不影响本轮对话。
        }
    }
}
