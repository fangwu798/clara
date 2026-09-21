package com.clara.orchestration.interaction;

import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ErrorCodes;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class InMemoryInteractionServiceTest {
    private MutableTime time;
    private RecordingCoordinator coordinator;
    private InMemoryInteractionService service;

    @Before
    public void setUp() {
        time = new MutableTime(100L); coordinator = new RecordingCoordinator();
        service = new InMemoryInteractionService(coordinator, new AnswerSchemaValidator() {
            public boolean isValid(String schema, Map<String, Object> answer) {
                return schema == null || "{}".equals(schema);
            }
        }, time, 4);
    }

    @Test
    public void submitsValidAnswerResumesAndReplaysIdempotently() {
        service.request(context("one", "node", 7L, 200L), textQuestion("answer"));
        InteractionFeedback feedback = feedback("one", 7L, "feedback-1", text("hello"));
        FeedbackResult result = service.submit(feedback);
        assertTrue(result.isResumed()); assertFalse(result.isIdempotentReplay());
        assertEquals("hello", ((Map<?, ?>) coordinator.scope.get("answer")).get("text"));
        assertEquals(InteractionState.ANSWERED, result.getRequest().getState());

        FeedbackResult replay = service.submit(feedback);
        assertTrue(replay.isIdempotentReplay()); assertEquals(1, coordinator.resumes.get());
    }

    @Test
    public void waitsForAllRequiredParallelAnswersAndPreservesOrder() {
        service.request(context("second", "node-2", 4L, 300L), textQuestion("secondAnswer"));
        service.request(new InteractionContext("first", "tenant", "run", "instance", "node-1",
                4L, 90L, 300L), textQuestion("firstAnswer"));
        List<InteractionRequest> pending = service.pending("tenant", "run");
        assertEquals("first", pending.get(0).getInteractionId());

        assertFalse(service.submit(feedback("first", 4L, "f1", text("A"))).isResumed());
        FeedbackResult last = service.submit(feedback("second", 4L, "f2", text("B")));
        assertTrue(last.isResumed()); assertEquals(2, coordinator.responses.size());
        assertTrue(coordinator.scope.containsKey("firstAnswer"));
        assertTrue(coordinator.scope.containsKey("secondAnswer"));
    }

    @Test
    public void enforcesTenantVersionExpiryAndTerminalState() {
        service.request(context("one", "node", 3L, 150L), textQuestion("answer"));
        assertError(ErrorCodes.INTERACTION_ACCESS_DENIED, new Runnable() {
            public void run() { service.get("other", "run", "one"); }
        });
        assertError(ErrorCodes.INTERACTION_CONFLICT, new Runnable() {
            public void run() { service.submit(feedback("one", 2L, "wrong-version", text("x"))); }
        });
        time.now = 150L;
        assertError(ErrorCodes.INTERACTION_EXPIRED, new Runnable() {
            public void run() { service.submit(feedback("one", 3L, "expired", text("x"))); }
        });
        assertEquals(InteractionState.EXPIRED, service.get("tenant", "run", "one").getState());
        assertEquals(1, coordinator.cancellations.get());
    }

    @Test
    public void validatesOptionsSchemaAndSensitiveFields() {
        Question selection = new Question(InteractionType.MULTI_SELECT, "choose",
                Arrays.asList(new InteractionOption("a", "A"), new InteractionOption("b", "B")),
                "{}", null, true, "choice", 100);
        service.request(context("select", "node", 1L, 200L), selection);
        final Map<String, Object> duplicate = new LinkedHashMap<String, Object>();
        duplicate.put("selectedOptionIds", Arrays.asList("a", "a"));
        assertError(ErrorCodes.INTERACTION_INVALID_ANSWER, new Runnable() {
            public void run() { service.submit(feedback("select", 1L, "duplicate", duplicate)); }
        });

        service.request(context("secret", "node-2", 1L, 200L), textQuestion("answer2"));
        final Map<String, Object> unsafe = text("ok"); unsafe.put("password", "plain");
        assertError(ErrorCodes.INTERACTION_INVALID_ANSWER, new Runnable() {
            public void run() { service.submit(feedback("secret", 1L, "unsafe", unsafe)); }
        });
    }

    @Test
    public void cancelsPendingInteractionAndEnforcesRunLimit() {
        service.request(context("one", "n1", 1L, 200L), textQuestion("a"));
        service.cancel("tenant", "one", "user cancelled");
        assertEquals(1, coordinator.cancellations.get());
        assertEquals(InteractionState.CANCELLED, service.get("tenant", "run", "one").getState());
        service.request(context("two", "n2", 1L, 200L), textQuestion("b"));
        service.request(context("three", "n3", 1L, 200L), textQuestion("c"));
        service.request(context("four", "n4", 1L, 200L), textQuestion("d"));
        assertError(ErrorCodes.INTERACTION_LIMIT_EXCEEDED, new Runnable() {
            public void run() { service.request(context("five", "n5", 1L, 200L), textQuestion("e")); }
        });
    }

    private InteractionContext context(String id, String node, long version, long expiry) {
        return new InteractionContext(id, "tenant", "run", "instance", node,
                version, time.now, expiry);
    }
    private static Question textQuestion(String key) {
        return new Question(InteractionType.TEXT_INPUT, "question", null, "{}", null,
                true, key, 20);
    }
    private static InteractionFeedback feedback(String id, long version, String key,
            Map<String, Object> answer) {
        return new InteractionFeedback("tenant", "run", id, "user", version, key, answer);
    }
    private static Map<String, Object> text(String value) {
        Map<String, Object> answer = new LinkedHashMap<String, Object>(); answer.put("text", value); return answer;
    }
    private static void assertError(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException exception) { assertEquals(code, exception.getError().getCode()); }
    }
    private static final class MutableTime implements TimeSource {
        long now; MutableTime(long now) { this.now = now; }
        public long currentTimeMillis() { return now; }
    }
    private static final class RecordingCoordinator implements InteractionResumeCoordinator {
        final AtomicInteger resumes = new AtomicInteger();
        final AtomicInteger cancellations = new AtomicInteger();
        List<InteractionResponse> responses;
        Map<String, Object> scope;
        public ResumeOutcome resumeAndRecord(InteractionRequest trigger,
                List<InteractionResponse> values, Map<String, Object> updates) {
            resumes.incrementAndGet(); responses = values; scope = updates;
            return new ResumeOutcome("RUNNING", trigger.getExpectedInstanceVersion() + 1L);
        }
        public void interactionCancelled(InteractionRequest request, String reason) {
            cancellations.incrementAndGet();
        }
    }
}
