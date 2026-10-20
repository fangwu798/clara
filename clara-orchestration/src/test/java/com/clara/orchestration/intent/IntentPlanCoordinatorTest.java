package com.clara.orchestration.intent;

import com.clara.core.memory.IContextTraceSink;
import com.clara.core.model.IRequest;
import com.clara.core.model.IResponse;
import com.clara.core.model.IStreamHandle;
import com.clara.core.model.IStreamModel;
import com.clara.core.model.IStreamObserver;
import com.clara.core.skill.IToolInvoker;
import com.clara.dto.tool.ToolResult;
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
        private int calls;

        private FakeModel(String... responses) {
            for (String response : responses) {
                this.responses.add(response);
            }
        }

        @Override
        public <R extends IResponse> R chat(IRequest request, Class<R> responseType) {
            calls++;
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
}
