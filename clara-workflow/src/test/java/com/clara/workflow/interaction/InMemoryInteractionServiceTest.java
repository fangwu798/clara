package com.clara.workflow.interaction;

import com.clara.core.interaction.IAnswerSchemaValidator;
import com.clara.core.interaction.IInteractionResumeCoordinator;
import com.clara.core.interaction.ITimeSource;
import com.clara.dto.interaction.FeedbackResult;
import com.clara.dto.interaction.InteractionContext;
import com.clara.dto.interaction.InteractionFeedback;
import com.clara.dto.interaction.InteractionRequest;
import com.clara.dto.interaction.InteractionResponse;
import com.clara.dto.interaction.Question;
import com.clara.dto.interaction.ResumeOutcome;
import com.clara.enums.interaction.InteractionStateEnum;
import com.clara.enums.interaction.InteractionTypeEnum;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link InMemoryInteractionService} 的单元测试：创建 / 列出待处理 / 获取 / 提交反馈 / 取消、
 * 幂等键重放与 expectedInstanceVersion 冲突。
 */
public class InMemoryInteractionServiceTest {

    private static final class RecordingCoordinator implements IInteractionResumeCoordinator {
        private int resumes;
        private int cancels;

        @Override
        public ResumeOutcome resumeAndRecord(InteractionRequest trigger, List<InteractionResponse> responses,
                Map<String, Object> scopeUpdates) {
            resumes++;
            return new ResumeOutcome("SUCCEEDED", 5L);
        }

        @Override
        public void interactionCancelled(InteractionRequest request, String reason) {
            cancels++;
        }
    }

    private static final class FixedTimeSource implements ITimeSource {
        private final long now;

        private FixedTimeSource(long now) {
            this.now = now;
        }

        @Override
        public long currentTimeMillis() {
            return now;
        }
    }

    private final IAnswerSchemaValidator schemaValidator = (schema, answer) -> true;

    private InMemoryInteractionService service(RecordingCoordinator coordinator, int maxInteractions) {
        return new InMemoryInteractionService(coordinator, schemaValidator,
                new FixedTimeSource(100L), maxInteractions);
    }

    private static InteractionContext context(String interactionId, String runId) {
        return new InteractionContext(interactionId, runId, "inst-1", "node-1", 0L, 100L, 10000L);
    }

    private static Question textQuestion(String scopeKey) {
        return Question.builder(InteractionTypeEnum.TEXT_INPUT, "Your answer?")
                .required(true).scopeKey(scopeKey).maxTextLength(100).build();
    }

    private static InteractionFeedback feedback(String interactionId, long expectedVersion,
            String idempotencyKey) {
        return new InteractionFeedback("r1", interactionId, "user", expectedVersion, idempotencyKey,
                Collections.<String, Object>singletonMap("text", "Alice"));
    }

    @Test
    public void createsAndListsPendingInteraction() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        InMemoryInteractionService service = service(coordinator, 5);

        InteractionRequest request = service.request(context("i1", "r1"), textQuestion("answer"));

        assertEquals(InteractionStateEnum.PENDING, request.getState());
        assertEquals(1, service.pending("r1").size());
        assertEquals("i1", service.pending("r1").get(0).getContext().getInteractionId());
        assertEquals(InteractionStateEnum.PENDING, service.get("r1", "i1").getState());
    }

    @Test
    public void submitsFeedbackAndResumes() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        InMemoryInteractionService service = service(coordinator, 5);
        service.request(context("i1", "r1"), textQuestion("answer"));

        FeedbackResult result = service.submit(feedback("i1", 0L, "idem-1"));

        assertEquals(InteractionStateEnum.ANSWERED, result.getRequest().getState());
        assertFalse(result.isIdempotentReplay());
        assertTrue(result.isResumed());
        assertEquals("SUCCEEDED", result.getResumeOutcome().getInstanceStatus());
        assertEquals(1, coordinator.resumes);
        assertTrue(service.pending("r1").isEmpty());
    }

    @Test
    public void replaysIdempotentFeedback() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        InMemoryInteractionService service = service(coordinator, 5);
        service.request(context("i1", "r1"), textQuestion("answer"));
        InteractionFeedback feedback = feedback("i1", 0L, "idem-1");

        service.submit(feedback);
        FeedbackResult replay = service.submit(feedback);

        assertTrue(replay.isIdempotentReplay());
        assertEquals(1, coordinator.resumes);
    }

    @Test
    public void rejectsStaleInstanceVersion() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        InMemoryInteractionService service = service(coordinator, 5);
        service.request(context("i1", "r1"), textQuestion("answer"));

        ClaraException error = assertThrows(ClaraException.class,
                () -> service.submit(feedback("i1", 9L, "idem-1")));

        assertEquals(ErrorCodes.INTERACTION_CONFLICT, error.getError().getCode());
    }

    @Test
    public void cancelsPendingInteraction() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        InMemoryInteractionService service = service(coordinator, 5);
        service.request(context("i1", "r1"), textQuestion("answer"));

        service.cancel("i1", "no longer needed");

        assertEquals(InteractionStateEnum.CANCELLED, service.get("r1", "i1").getState());
        assertEquals(1, coordinator.cancels);
    }

    @Test
    public void enforcesMaxInteractionsPerRun() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        InMemoryInteractionService service = service(coordinator, 1);
        service.request(context("i1", "r1"), textQuestion("answer"));

        ClaraException error = assertThrows(ClaraException.class,
                () -> service.request(context("i2", "r1"), textQuestion("answer")));

        assertEquals(ErrorCodes.INTERACTION_LIMIT_EXCEEDED, error.getError().getCode());
    }

    @Test
    public void deniesAccessToForeignRun() {
        RecordingCoordinator coordinator = new RecordingCoordinator();
        InMemoryInteractionService service = service(coordinator, 5);
        service.request(context("i1", "r1"), textQuestion("answer"));

        ClaraException error = assertThrows(ClaraException.class, () -> service.get("r2", "i1"));

        assertEquals(ErrorCodes.INTERACTION_ACCESS_DENIED, error.getError().getCode());
    }
}
