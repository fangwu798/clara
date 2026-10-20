package com.clara.workflow.interaction;

import com.clara.core.interaction.IAnswerSchemaValidator;
import com.clara.core.interaction.IInteractionResumeCoordinator;
import com.clara.core.interaction.ITimeSource;
import com.clara.dto.interaction.FeedbackResult;
import com.clara.dto.interaction.InteractionContext;
import com.clara.dto.interaction.InteractionFeedback;
import com.clara.dto.interaction.InteractionOption;
import com.clara.dto.interaction.InteractionRequest;
import com.clara.dto.interaction.InteractionResponse;
import com.clara.dto.interaction.Question;
import com.clara.dto.interaction.ResumeOutcome;
import com.clara.enums.interaction.InteractionStateEnum;
import com.clara.enums.interaction.InteractionTypeEnum;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Remaining validation, expiry and resume rules of the in-memory interaction service.
 */
public class InMemoryInteractionServiceValidationTest {

    private static final class Coordinator implements IInteractionResumeCoordinator {
        private int cancels;
        private int resumes;
        private List<InteractionResponse> responses;
        private Map<String, Object> updates;

        @Override
        public ResumeOutcome resumeAndRecord(InteractionRequest trigger, List<InteractionResponse> responses,
                Map<String, Object> scopeUpdates) {
            resumes++;
            this.responses = responses;
            this.updates = scopeUpdates;
            return new ResumeOutcome("SUCCEEDED", 1L);
        }

        @Override
        public void interactionCancelled(InteractionRequest request, String reason) {
            cancels++;
        }
    }

    private static final class Clock implements ITimeSource {
        private long now;

        private Clock(long now) {
            this.now = now;
        }

        @Override
        public long currentTimeMillis() {
            return now;
        }
    }

    private final Coordinator coordinator = new Coordinator();
    private final Clock clock = new Clock(100L);

    private InMemoryInteractionService service() {
        return service(5, (schema, answer) -> true);
    }

    private InMemoryInteractionService service(int max, IAnswerSchemaValidator validator) {
        return new InMemoryInteractionService(coordinator, validator, clock, max);
    }

    @Test
    public void validatesConstructorDependencies() {
        assertIllegalArgument(() -> new InMemoryInteractionService(null, (schema, answer) -> true, clock, 1));
        assertIllegalArgument(() -> new InMemoryInteractionService(coordinator, null, clock, 1));
        assertIllegalArgument(() -> new InMemoryInteractionService(coordinator, (schema, answer) -> true, null, 1));
        assertIllegalArgument(() -> new InMemoryInteractionService(coordinator, (schema, answer) -> true, clock, 0));
    }

    @Test
    public void requestRejectsNullAndHandlesDuplicateIdentity() {
        InMemoryInteractionService service = service();
        assertIllegalArgument(() -> service.request(null, text("a")));
        assertIllegalArgument(() -> service.request(context("i1", "r1", 0L, 10000L), null));

        InteractionRequest first = service.request(context("i1", "r1", 0L, 10000L), text("a"));
        InteractionRequest again = service.request(context("i1", "r1", 0L, 10000L), text("other"));
        assertSame(first, again);

        assertError(ErrorCodes.INTERACTION_CONFLICT, () ->
                service.request(context("i1", "r2", 0L, 10000L), text("a")));
        assertError(ErrorCodes.INTERACTION_CONFLICT, () ->
                service.request(context("i1", "r1", 1L, 10000L), text("a")));
    }

    @Test
    public void pendingValidatesRunAndSortsByCreationThenId() {
        InMemoryInteractionService service = service();
        assertIllegalArgument(() -> service.pending(null));
        assertIllegalArgument(() -> service.pending(" "));
        service.request(context("b", "r1", 0L, 10000L), text("a"));
        service.request(context("a", "r1", 0L, 10000L), text("a"));
        clock.now = 300L;
        service.request(context("c", "r1", 0L, 10000L), text("a"));

        List<InteractionRequest> pending = service.pending("r1");

        assertEquals(3, pending.size());
        assertEquals("a", pending.get(0).getContext().getInteractionId());
        assertEquals("b", pending.get(1).getContext().getInteractionId());
        assertEquals("c", pending.get(2).getContext().getInteractionId());
    }

    @Test
    public void getRejectsBlankIdsAndUnknownInteraction() {
        InMemoryInteractionService service = service();
        assertIllegalArgument(() -> service.get(null, "i1"));
        assertIllegalArgument(() -> service.get("r1", " "));
        assertError(ErrorCodes.INTERACTION_NOT_FOUND, () -> service.get("r1", "missing"));
    }

    @Test
    public void submitValidatesFeedbackAndIdentity() {
        InMemoryInteractionService service = service();
        assertIllegalArgument(() -> service.submit(null));
        assertError(ErrorCodes.INTERACTION_NOT_FOUND, () -> service.submit(feedback("missing", "r1", 0L, "k", "x")));

        service.request(context("i1", "r1", 0L, 10000L), text("a"));
        assertError(ErrorCodes.INTERACTION_ACCESS_DENIED, () -> service.submit(feedback("i1", "r2", 0L, "k", "x")));
    }

    @Test
    public void replayingKeyForAnotherInteractionIsConflict() {
        InMemoryInteractionService service = service();
        service.request(context("i1", "r1", 0L, 10000L), text("a"));
        service.request(context("i2", "r1", 0L, 10000L), text("a"));
        service.submit(feedback("i1", "r1", 0L, "same", "x"));

        assertError(ErrorCodes.INTERACTION_CONFLICT,
                () -> service.submit(feedback("i2", "r1", 0L, "same", "x")));
    }

    @Test
    public void expiredInteractionIsCancelledAndRejected() {
        InMemoryInteractionService service = service();
        service.request(context("i1", "r1", 0L, 1000L), text("a"));
        clock.now = 1000L;

        assertTrue(service.pending("r1").isEmpty());
        assertEquals(1, coordinator.cancels);
        assertEquals(InteractionStateEnum.EXPIRED, service.get("r1", "i1").getState());
        assertError(ErrorCodes.INTERACTION_EXPIRED,
                () -> service.submit(feedback("i1", "r1", 0L, "k", "x")));
    }

    @Test
    public void submittingAnAnsweredInteractionIsConflict() {
        InMemoryInteractionService service = service();
        service.request(context("i1", "r1", 0L, 10000L), text("a"));
        service.submit(feedback("i1", "r1", 0L, "first", "x"));
        assertError(ErrorCodes.INTERACTION_CONFLICT,
                () -> service.submit(feedback("i1", "r1", 0L, "second", "y")));
    }

    @Test
    public void usesDefaultAnswerWhenSubmissionIsEmpty() {
        InMemoryInteractionService service = service();
        Question question = Question.builder(InteractionTypeEnum.TEXT_INPUT, "q")
                .required(true).scopeKey("answer").maxTextLength(10)
                .defaultAnswer(Collections.<String, Object>singletonMap("text", "preset")).build();
        service.request(context("i1", "r1", 0L, 10000L), question);

        FeedbackResult result = service.submit(new InteractionFeedback("r1", "i1", "u", 0L, "k",
                Collections.<String, Object>emptyMap()));

        assertEquals("preset", result.getRequest().getResponse().getAnswer().get("text"));
    }

    @Test
    public void rejectsSensitiveAnswerKeysButAllowsSecretRef() {
        InMemoryInteractionService service = service();
        service.request(context("i1", "r1", 0L, 10000L), text("a"));
        service.request(context("i2", "r1", 0L, 10000L), text("b"));
        Map<String, Object> sensitive = new HashMap<>();
        sensitive.put("text", "x");
        sensitive.put("password", "p");
        assertError(ErrorCodes.INTERACTION_INVALID_ANSWER,
                () -> service.submit(new InteractionFeedback("r1", "i1", "u", 0L, "k1", sensitive)));
        service.cancel("i1", "invalid answer discarded");

        Map<String, Object> allowed = new HashMap<>();
        allowed.put("text", "x");
        allowed.put("secretRef", "vault://key");
        FeedbackResult result = service.submit(new InteractionFeedback("r1", "i2", "u", 0L, "k2", allowed));
        assertEquals(InteractionStateEnum.ANSWERED, result.getRequest().getState());
    }

    @Test
    public void validatesTextInputAndSchema() {
        InMemoryInteractionService service = service();
        Question shortText = Question.builder(InteractionTypeEnum.TEXT_INPUT, "q")
                .required(true).scopeKey("a").maxTextLength(3).build();
        service.request(context("i1", "r1", 0L, 10000L), shortText);
        assertInvalid(service, "i1", "abcd");
        assertInvalid(service, "i1", "   ");

        InMemoryInteractionService strict = service(5, (schema, answer) -> false);
        strict.request(context("i2", "r2", 0L, 10000L), text("a"));
        assertError(ErrorCodes.INTERACTION_INVALID_ANSWER,
                () -> strict.submit(feedback("i2", "r2", 0L, "k", "x")));
    }

    @Test
    public void validatesSelectionQuestions() {
        InMemoryInteractionService service = service();
        service.request(context("single", "r1", 0L, 10000L), select(InteractionTypeEnum.SINGLE_SELECT, true));
        assertInvalid(service, "single", Collections.singletonMap("selectedOptionId", "c"));
        assertInvalid(service, "single", Collections.singletonMap("selectedOptionId", " "));
        assertTrue(service.submit(new InteractionFeedback("r1", "single", "u", 0L, "s1",
                Collections.singletonMap("selectedOptionId", "a"))).isResumed());

        service.request(context("multi", "r1", 0L, 10000L), select(InteractionTypeEnum.MULTI_SELECT, true));
        assertInvalid(service, "multi", Collections.singletonMap("selectedOptionIds", Collections.emptyList()));
        assertInvalid(service, "multi", Collections.singletonMap("selectedOptionIds", Arrays.asList("a", "a")));
        assertInvalid(service, "multi", Collections.singletonMap("selectedOptionIds", Collections.singletonList("c")));
        assertInvalid(service, "multi", Collections.singletonMap("selectedOptionIds", "not-a-list"));
        assertTrue(service.submit(new InteractionFeedback("r1", "multi", "u", 0L, "m1",
                Collections.singletonMap("selectedOptionIds", Arrays.asList("a", "b")))).isResumed());
    }

    @Test
    public void allowsEmptyOptionalMultiSelect() {
        InMemoryInteractionService service = service();
        service.request(context("multi", "r1", 0L, 10000L), select(InteractionTypeEnum.MULTI_SELECT, false));
        assertTrue(service.submit(new InteractionFeedback("r1", "multi", "u", 0L, "k",
                Collections.singletonMap("selectedOptionIds", Collections.emptyList()))).isResumed());
    }

    @Test
    public void validatesConfirmationFileAssetAndChangeApproval() {
        InMemoryInteractionService service = service();
        service.request(context("confirm", "r1", 0L, 10000L), simple(InteractionTypeEnum.CONFIRMATION, true));
        assertInvalid(service, "confirm", Collections.singletonMap("decision", "MAYBE"));
        assertTrue(service.submit(new InteractionFeedback("r1", "confirm", "u", 0L, "c1",
                Collections.singletonMap("decision", "CONFIRM"))).isResumed());

        service.request(context("file", "r1", 0L, 10000L), simple(InteractionTypeEnum.FILE, true));
        assertInvalid(service, "file", Collections.singletonMap("fileRef", ""));
        assertTrue(service.submit(new InteractionFeedback("r1", "file", "u", 0L, "f1",
                Collections.singletonMap("fileRef", "upload://f"))).isResumed());

        service.request(context("asset", "r1", 0L, 10000L), simple(InteractionTypeEnum.ASSET_UPLOAD, true));
        assertInvalid(service, "asset", Collections.singletonMap("assetId", " "));
        assertTrue(service.submit(new InteractionFeedback("r1", "asset", "u", 0L, "a1",
                Collections.singletonMap("assetId", "asset-1"))).isResumed());

        service.request(context("change", "r1", 0L, 10000L), simple(InteractionTypeEnum.CHANGE_APPROVAL, true));
        assertInvalid(service, "change", Collections.singletonMap("changeSetId", "c"));
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("changeSetId", "c");
        approval.put("revision", 1);
        approval.put("changeSetDigest", "d1");
        approval.put("previewDigest", "d2");
        approval.put("decision", "APPROVE");
        assertTrue(service.submit(new InteractionFeedback("r1", "change", "u", 0L, "ch1", approval)).isResumed());
    }

    @Test
    public void waitsForOtherRequiredInteractionsBeforeResuming() {
        InMemoryInteractionService service = service();
        service.request(context("i1", "r1", 0L, 10000L), text("a"));
        service.request(context("i2", "r1", 0L, 10000L), text("b"));

        FeedbackResult first = service.submit(feedback("i1", "r1", 0L, "k1", "x"));
        assertNull(first.getResumeOutcome());

        FeedbackResult second = service.submit(feedback("i2", "r1", 0L, "k2", "y"));
        assertTrue(second.isResumed());
        assertEquals(2, coordinator.responses.size());
        assertEquals("x", ((Map<?, ?>) coordinator.updates.get("a")).get("text"));
        assertEquals("y", ((Map<?, ?>) coordinator.updates.get("b")).get("text"));
    }

    @Test
    public void cancelValidatesReasonAndState() {
        InMemoryInteractionService service = service();
        assertIllegalArgument(() -> service.cancel(null, "r"));
        assertIllegalArgument(() -> service.cancel("i1", " "));
        assertError(ErrorCodes.INTERACTION_NOT_FOUND, () -> service.cancel("missing", "r"));

        service.request(context("i1", "r1", 0L, 10000L), text("a"));
        service.cancel("i1", "reason");
        service.cancel("i1", "again");
        assertEquals(1, coordinator.cancels);

        service.request(context("i2", "r1", 0L, 10000L), text("a"));
        service.submit(feedback("i2", "r1", 0L, "k", "x"));
        assertError(ErrorCodes.INTERACTION_CONFLICT, () -> service.cancel("i2", "reason"));
    }

    private void assertInvalid(InMemoryInteractionService service, String id, Object answer) {
        Map<String, Object> values = answer instanceof Map
                ? new HashMap<>((Map<String, Object>) answer)
                : Collections.singletonMap("text", (String) answer);
        assertError(ErrorCodes.INTERACTION_INVALID_ANSWER,
                () -> service.submit(new InteractionFeedback("r1", id, "u", 0L, "bad-" + id + "-" + System.nanoTime(),
                        values)));
    }

    private void assertInvalid(InMemoryInteractionService service, String id, String text) {
        assertInvalid(service, id, (Object) text);
    }

    private static Question text(String scopeKey) {
        return Question.builder(InteractionTypeEnum.TEXT_INPUT, "q")
                .required(true).scopeKey(scopeKey).maxTextLength(100).build();
    }

    private static Question simple(InteractionTypeEnum type, boolean required) {
        return Question.builder(type, "q").required(required).scopeKey("answer").maxTextLength(100).build();
    }

    private static Question select(InteractionTypeEnum type, boolean required) {
        return Question.builder(type, "q").required(required).scopeKey("answer").maxTextLength(100)
                .options(Arrays.asList(new InteractionOption("a", "A"), new InteractionOption("b", "B")))
                .build();
    }

    private static InteractionContext context(String interactionId, String runId, long expectedVersion,
            long expiresAt) {
        return new InteractionContext(interactionId, runId, "inst-1", "node-1", expectedVersion, 100L, expiresAt);
    }

    private static InteractionFeedback feedback(String interactionId, String runId, long expectedVersion,
            String idempotencyKey, String text) {
        return new InteractionFeedback(runId, interactionId, "u", expectedVersion, idempotencyKey,
                Collections.<String, Object>singletonMap("text", text));
    }

    private static void assertError(String code, Runnable action) {
        try {
            action.run();
            fail("expected " + code);
        } catch (ClaraException expected) {
            assertEquals(code, expected.getError().getCode());
        }
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
