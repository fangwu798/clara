package com.clara.orchestration.intent;

import com.clara.core.memory.IContextTraceSink;
import com.clara.core.model.IRequest;
import com.clara.core.model.IResponse;
import com.clara.core.model.IStreamHandle;
import com.clara.core.model.IStreamModel;
import com.clara.core.model.IStreamObserver;
import com.clara.core.skill.IToolInvoker;
import com.clara.dto.tool.ToolResult;
import com.clara.enums.RoleEnum;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link IntentPlanCoordinator} 单元测试：简单任务跳过计划、复杂任务经工具校验、校验失败修正与降级。
 */
public class IntentPlanCoordinatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SIMPLE = "{\"mode\":\"simple\",\"reason\":\"单点问答\"}";

    private static final String PLAN = "{\"mode\":\"complex\",\"reason\":\"跨文件修改\",\"goal\":\"实现登录\","
            + "\"steps\":[{\"id\":\"s1\",\"title\":\"读取现有代码\"},"
            + "{\"id\":\"s2\",\"title\":\"实现接口\",\"dependencies\":[\"s1\"]}]}";

    private static final String INVALID_PLAN = "{\"mode\":\"complex\",\"goal\":\"实现登录\","
            + "\"steps\":[{\"id\":\"s1\",\"title\":\"a\"},{\"id\":\"s1\",\"title\":\"b\"}]}";

    @Test
    public void simpleIntentSkipsPlanAndTool() {
        FakeModel model = new FakeModel(SIMPLE);
        AtomicInteger toolCalls = new AtomicInteger();
        IntentPlanCoordinator coordinator = coordinator(model, tool(toolCalls), 1);

        PreparedIntent prepared = coordinator.prepare("解释一下这个方法", "m1");

        assertFalse(prepared.isComplex());
        assertNull(prepared.prompt());
        assertEquals(0, toolCalls.get());
        assertEquals(1, model.calls);
    }

    @Test
    public void intentDecisionCarriesOnlyRouterPromptAndCurrentInput() {
        FakeModel model = new FakeModel(SIMPLE);
        IntentPlanCoordinator coordinator = coordinator(model, tool(new AtomicInteger()), 0);

        coordinator.prepare("只判断这一句", "m1");

        HttpRequest request = model.requests.get(0);
        assertEquals(2, request.getMessages().size());
        assertEquals(RoleEnum.SYSTEM, request.getMessages().get(0).getRoleType());
        assertEquals(RoleEnum.USER, request.getMessages().get(1).getRoleType());
        assertEquals("只判断这一句", ((UserContent) request.getMessages().get(1)).getContent());
    }

    @Test
    public void complexIntentUsesValidatedPlan() {
        FakeModel model = new FakeModel(PLAN);
        AtomicInteger toolCalls = new AtomicInteger();
        IntentPlanCoordinator coordinator = coordinator(model, tool(toolCalls), 1);

        PreparedIntent prepared = coordinator.prepare("帮我实现登录并验证", "m1");

        assertTrue(prepared.isComplex());
        assertNotNull(prepared.getPlan());
        assertEquals(2, prepared.getPlan().getSteps().size());
        assertTrue(prepared.prompt().contains("目标：实现登录"));
        assertTrue(prepared.prompt().contains("[s2] 实现接口"));
        assertEquals(1, toolCalls.get());
    }

    @Test
    public void repairsInvalidPlanOnce() {
        FakeModel model = new FakeModel(INVALID_PLAN, PLAN);
        AtomicInteger toolCalls = new AtomicInteger();
        IntentPlanCoordinator coordinator = coordinator(model, tool(toolCalls), 1);

        PreparedIntent prepared = coordinator.prepare("帮我实现登录并验证", "m1");

        assertTrue(prepared.isComplex());
        assertEquals(2, model.calls);
        assertEquals(2, toolCalls.get());
    }

    @Test
    public void fallsBackToSimpleWhenPlanStillInvalid() {
        FakeModel model = new FakeModel(INVALID_PLAN);
        IntentPlanCoordinator coordinator = coordinator(model, tool(new AtomicInteger()), 0);

        PreparedIntent prepared = coordinator.prepare("帮我实现登录并验证", "m1");

        assertFalse(prepared.isComplex());
        assertFalse(prepared.getReason().isEmpty());
    }

    @Test
    public void fallsBackToSimpleWhenModelReturnsGarbage() {
        FakeModel model = new FakeModel("这不是 JSON");
        IntentPlanCoordinator coordinator = coordinator(model, tool(new AtomicInteger()), 0);

        PreparedIntent prepared = coordinator.prepare("帮我实现登录并验证", "m1");

        assertFalse(prepared.isComplex());
    }

    @Test
    public void trivialInputSkipsModelCall() {
        FakeModel model = new FakeModel(SIMPLE);
        IntentPlanCoordinator coordinator = coordinator(model, tool(new AtomicInteger()), 1);

        PreparedIntent prepared = coordinator.prepare("你好", "m1");

        assertFalse(prepared.isComplex());
        assertEquals(0, model.calls);
    }

    @Test
    public void reportsIntentPlanAndValidationPhases() {
        RecordingTrace trace = new RecordingTrace();
        IntentPlanCoordinator coordinator = new IntentPlanCoordinator(new FakeModel(PLAN),
                tool(new AtomicInteger()), new ExecutionPlanValidator(12), 1, trace);

        coordinator.prepare("帮我实现登录并验证", "m1");

        assertTrue(trace.has("intent", "判断中"));
        assertTrue(trace.has("intent", "复杂任务"));
        assertTrue(trace.has("plan", "生成完成"));
        assertTrue(trace.has("validation", "校验中"));
        assertTrue(trace.has("validation", "通过"));
        assertTrue(trace.has("plan", "已生成"));
    }

    @Test
    public void reportsValidationFailureAndRepair() {
        RecordingTrace trace = new RecordingTrace();
        IntentPlanCoordinator coordinator = new IntentPlanCoordinator(
                new FakeModel(INVALID_PLAN, PLAN), tool(new AtomicInteger()),
                new ExecutionPlanValidator(12), 1, trace);

        coordinator.prepare("帮我实现登录并验证", "m1");

        assertTrue(trace.has("validation", "未通过"));
        assertTrue(trace.has("plan", "修正中"));
    }

    private static IntentPlanCoordinator coordinator(FakeModel model, IToolInvoker tool, int repairs) {
        return new IntentPlanCoordinator(model, tool, new ExecutionPlanValidator(12), repairs, null);
    }

    private static IToolInvoker tool(AtomicInteger calls) {
        ExecutionPlanValidator validator = new ExecutionPlanValidator(12);
        return invocation -> {
            calls.incrementAndGet();
            try {
                JsonNode root = JSON.readTree(invocation.arguments());
                validator.validate(root.get("plan"));
                return new ToolResult(invocation.callId(), "{\"ok\":true,\"stepCount\":2}");
            } catch (Exception failure) {
                return new ToolResult(invocation.callId(), error(failure.getMessage()));
            }
        };
    }

    private static String error(String message) {
        return "{\"ok\":false,\"error\":\"" + (message == null ? "校验失败" : message.replace("\"", "'")) + "\"}";
    }

    private static final class FakeModel implements IStreamModel {

        private final Deque<String> responses = new ArrayDeque<>();
        private final List<HttpRequest> requests = new ArrayList<>();
        private int calls;

        private FakeModel(String... responses) {
            for (String response : responses) {
                this.responses.add(response);
            }
        }

        @Override
        public <R extends IResponse> R chat(IRequest request, Class<R> responseType) {
            calls++;
            requests.add((HttpRequest) request);
            HttpResponse response = new HttpResponse();
            response.setContent(responses.isEmpty() ? "" : responses.poll());
            return responseType.cast(response);
        }

        @Override
        public <R extends IResponse> IStreamHandle chat(IRequest request, Class<R> responseType,
                IStreamObserver observer) {
            throw new UnsupportedOperationException("not used");
        }
    }

    /** 记录阶段事件，便于断言「意图 / 计划 / 校验」三段的输出顺序与状态文本。 */
    private static final class RecordingTrace implements IContextTraceSink {

        private final List<String> events = new ArrayList<>();

        @Override
        public void onTrace(String source, int hits, long elapsedMillis, List<String> details) {
            events.add(source + "|" + hits);
        }

        @Override
        public void onTrace(String source, String status, boolean success, long elapsedMillis,
                List<String> details) {
            events.add(source + "|" + status + "|" + success);
        }

        private boolean has(String source, String status) {
            String prefix = source + "|" + status + "|";
            return events.stream().anyMatch(event -> event.startsWith(prefix));
        }
    }

    /**
     * 构造参数校验：模型与校验器必填，修正次数不得为负。
     */
    @Test
    public void rejectsInvalidConstructorArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntentPlanCoordinator(null, null, new ExecutionPlanValidator(5), 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new IntentPlanCoordinator(new FakeModel(SIMPLE), null, null, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new IntentPlanCoordinator(new FakeModel(SIMPLE), null, new ExecutionPlanValidator(5), -1, null));
    }

    /**
     * 模型空输出视为判断失败并降级为简单任务，而不是抛异常中断本轮对话。
     */
    @Test
    public void blankModelOutputFallsBackToSimple() {
        FakeModel model = new FakeModel("   ");
        IntentPlanCoordinator coordinator = coordinator(model, tool(new AtomicInteger()), 1);

        PreparedIntent prepared = coordinator.prepare("帮我实现登录并验证", "m1");

        assertFalse(prepared.isComplex());
        assertTrue(prepared.getReason().contains("意图判断失败"));
    }

    /**
     * 兼容「计划包在 plan 对象里」的输出：判定为复杂任务并把内层计划交给校验。
     */
    @Test
    public void unwrapsNestedPlanObject() {
        String nested = "{\"plan\":{\"goal\":\"实现登录\",\"steps\":[{\"id\":\"s1\",\"title\":\"读取代码\"}]}}";
        IntentPlanCoordinator coordinator = coordinator(new FakeModel(nested), tool(new AtomicInteger()), 0);

        PreparedIntent prepared = coordinator.prepare("帮我实现登录", "m1");

        assertTrue(prepared.isComplex());
        assertEquals(1, prepared.getPlan().getSteps().size());
        assertTrue(prepared.prompt().contains("目标：实现登录"));
        assertTrue(prepared.prompt().contains("[s1] 读取代码"));
    }

    /**
     * 没有 mode 字段但有步骤数组时按复杂任务处理；显式 simple 时即使带步骤也不生成计划。
     */
    @Test
    public void infersComplexityFromStepsUnlessModeIsSimple() {
        String stepsOnly = "{\"goal\":\"实现登录\",\"steps\":[{\"id\":\"s1\",\"title\":\"a\"}]}";
        PreparedIntent inferred = coordinator(new FakeModel(stepsOnly), tool(new AtomicInteger()), 0)
                .prepare("多步骤任务", "m1");
        assertTrue(inferred.isComplex());

        String simpleWithSteps = "{\"mode\":\"simple\",\"reason\":\"直接回答\","
                + "\"steps\":[{\"id\":\"s1\",\"title\":\"a\"}]}";
        AtomicInteger toolCalls = new AtomicInteger();
        PreparedIntent explicit = coordinator(new FakeModel(simpleWithSteps), tool(toolCalls), 0)
                .prepare("多步骤任务", "m1");
        assertFalse(explicit.isComplex());
        assertEquals(0, toolCalls.get());
    }

    /**
     * 模型偶尔输出 Markdown 代码围栏，解析前应剥离围栏只保留 JSON。
     */
    @Test
    public void acceptsFencedJsonOutput() {
        String fenced = "```json\n" + PLAN + "\n```";
        PreparedIntent prepared = coordinator(new FakeModel(fenced), tool(new AtomicInteger()), 0)
                .prepare("帮我实现登录并验证", "m1");

        assertTrue(prepared.isComplex());
        assertEquals(2, prepared.getPlan().getSteps().size());
    }

    /**
     * 校验工具判定失败：ok=false 且没有 error 字段时使用默认原因。
     */
    @Test
    public void toolFailureWithoutErrorUsesDefaultReason() {
        IToolInvoker failing = invocation -> new ToolResult(invocation.callId(), "{\"ok\":false}");
        PreparedIntent prepared = coordinator(new FakeModel(PLAN), failing, 0)
                .prepare("帮我实现登录并验证", "m1");

        assertFalse(prepared.isComplex());
        assertTrue(prepared.getReason().contains("计划格式校验未通过"));
    }

    /**
     * 校验工具没有返回值：按「未返回结果」处理，而不是当作通过。
     */
    @Test
    public void missingToolResultIsRejected() {
        IToolInvoker empty = invocation -> null;
        PreparedIntent prepared = coordinator(new FakeModel(PLAN), empty, 0)
                .prepare("帮我实现登录并验证", "m1");

        assertFalse(prepared.isComplex());
        assertTrue(prepared.getReason().contains("未返回结果"));
    }

    /**
     * 校验工具本身异常时退回本地校验，计划能力不依赖宿主是否正确注册工具。
     */
    @Test
    public void throwingToolFallsBackToLocalValidation() {
        RecordingTrace trace = new RecordingTrace();
        IToolInvoker broken = invocation -> {
            throw new IllegalStateException("tool down");
        };
        IntentPlanCoordinator coordinator = new IntentPlanCoordinator(new FakeModel(PLAN), broken,
                new ExecutionPlanValidator(12), 0, trace);

        PreparedIntent prepared = coordinator.prepare("帮我实现登录并验证", "m1");

        assertTrue(prepared.isComplex());
        assertTrue(trace.has("validation", "工具不可用"));
        assertTrue(trace.has("validation", "通过（本地）"));
    }

    /**
     * 修正后模型改判为简单任务：直接结束，不再继续修计划。
     */
    @Test
    public void repairMayDowngradeToSimpleTask() {
        RecordingTrace trace = new RecordingTrace();
        IntentPlanCoordinator coordinator = new IntentPlanCoordinator(
                new FakeModel(INVALID_PLAN, SIMPLE), tool(new AtomicInteger()),
                new ExecutionPlanValidator(12), 1, trace);

        PreparedIntent prepared = coordinator.prepare("帮我实现登录并验证", "m1");

        assertFalse(prepared.isComplex());
        assertTrue(trace.has("intent", "简单任务"));
    }
}