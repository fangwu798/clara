package com.clara.workflow.interaction;

import com.clara.core.interaction.IAnswerSchemaValidator;
import com.clara.core.interaction.IInteractionResumeCoordinator;
import com.clara.core.interaction.IInteractionService;
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
import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 在内存中管理待处理交互，并在收到反馈后触发恢复。
 */
public final class InMemoryInteractionService implements IInteractionService {

    private final IInteractionResumeCoordinator coordinator;
    private final IAnswerSchemaValidator schemaValidator;
    private final ITimeSource timeSource;
    private final int maxInteractionsPerRun;
    private final Map<String, InteractionRequest> requests =
            new LinkedHashMap<>();
    private final Map<String, FeedbackResult> feedbackByKey =
            new LinkedHashMap<>();

    public InMemoryInteractionService(IInteractionResumeCoordinator coordinator,
            IAnswerSchemaValidator schemaValidator, ITimeSource timeSource, int maxInteractionsPerRun) {
        if (coordinator == null || schemaValidator == null || timeSource == null
                || maxInteractionsPerRun <= 0) {
            throw new IllegalArgumentException("interaction service dependencies are invalid");
        }
        this.coordinator = coordinator;
        this.schemaValidator = schemaValidator;
        this.timeSource = timeSource;
        this.maxInteractionsPerRun = maxInteractionsPerRun;
    }

    public synchronized InteractionRequest request(InteractionContext context, Question question) {
        if (context == null || question == null) {
            throw new IllegalArgumentException("context and question are required");
        }
        InteractionRequest existing = requests.get(context.getInteractionId());
        if (existing != null) {
            if (sameIdentity(existing, context)) {
                return existing;
            }
            throw conflict();
        }
        int count = 0;
        for (InteractionRequest value : requests.values()) {
            if (value.getContext().getRunId().equals(context.getRunId())) {
                count++;
            }
        }
        if (count >= maxInteractionsPerRun) {
            throw InteractionErrors.error(ErrorCodes.INTERACTION_LIMIT_EXCEEDED, ErrorCategory.RESOURCE_LIMIT);
        }
        InteractionRequest created = new InteractionRequest(context, question,
                InteractionStateEnum.PENDING, null, null);
        requests.put(context.getInteractionId(), created);
        return created;
    }

    @Override
    public synchronized List<InteractionRequest> pending(String runId) {
        requireId(runId);
        expire(timeSource.currentTimeMillis());
        List<InteractionRequest> result = new ArrayList<>();
        for (InteractionRequest request : requests.values()) {
            if (request.getContext().getRunId().equals(runId)
                    && request.getState() == InteractionStateEnum.PENDING) {
                result.add(request);
            }
        }
        Collections.sort(result, new Comparator<InteractionRequest>() {
            @Override
            public int compare(InteractionRequest one, InteractionRequest two) {
                int time = Long.compare(one.getContext().getCreatedAtEpochMillis(),
                        two.getContext().getCreatedAtEpochMillis());
                return time != 0 ? time : one.getContext().getInteractionId()
                        .compareTo(two.getContext().getInteractionId());
            }
        });
        return Collections.unmodifiableList(result);
    }

    @Override
    public synchronized InteractionRequest get(String runId, String interactionId) {
        requireId(runId);
        requireId(interactionId);
        expire(timeSource.currentTimeMillis());
        InteractionRequest request = requests.get(interactionId);
        if (request == null) {
            throw notFound();
        }
        if (!request.getContext().getRunId().equals(runId)) {
            throw denied();
        }
        return request;
    }

    @Override
    public synchronized FeedbackResult submit(InteractionFeedback feedback) {
        if (feedback == null) {
            throw new IllegalArgumentException("feedback must not be null");
        }
        String feedbackKey = feedback.getRunId() + ":" + feedback.getIdempotencyKey();
        FeedbackResult replay = feedbackByKey.get(feedbackKey);
        if (replay != null) {
            if (!replay.getRequest().getContext().getInteractionId().equals(feedback.getInteractionId())) {
                throw conflict();
            }
            return new FeedbackResult(replay.getRequest(), true, replay.getResumeOutcome());
        }
        InteractionRequest request = requests.get(feedback.getInteractionId());
        if (request == null) {
            throw notFound();
        }
        if (!request.getContext().getRunId().equals(feedback.getRunId())) {
            throw denied();
        }
        long now = timeSource.currentTimeMillis();
        if (request.getState() == InteractionStateEnum.PENDING
                && now >= request.getContext().getExpiresAtEpochMillis()) {
            request = expireRequest(request);
        }
        if (request.getState() == InteractionStateEnum.EXPIRED) {
            throw InteractionErrors.error(ErrorCodes.INTERACTION_EXPIRED, ErrorCategory.CONFLICT);
        }
        if (request.getState() != InteractionStateEnum.PENDING
                || feedback.getExpectedInstanceVersion() != request.getContext().getExpectedInstanceVersion()) {
            throw conflict();
        }
        Map<String, Object> answer = effectiveAnswer(request.getQuestion(), feedback.getAnswer());
        validateAnswer(request.getQuestion(), answer);
        InteractionResponse response = new InteractionResponse(request.getContext().getInteractionId(),
                answer, feedback.getResponderId(), feedback.getIdempotencyKey(), now);
        InteractionRequest answered = new InteractionRequest(request.getContext(), request.getQuestion(),
                InteractionStateEnum.ANSWERED, response, null);
        ResumeOutcome outcome = null;
        if (!hasOtherRequiredInteraction(request)) {
            Map<String, Object> scopeUpdates = new LinkedHashMap<>();
            List<InteractionResponse> responses = new ArrayList<>();
            for (InteractionRequest value : requests.values()) {
                if (value.getContext().getRunId().equals(request.getContext().getRunId())
                        && value.getContext().getInstanceId().equals(request.getContext().getInstanceId())
                        && value.getState() == InteractionStateEnum.ANSWERED) {
                    responses.add(value.getResponse());
                    scopeUpdates.put(value.getQuestion().getScopeKey(), value.getResponse().getAnswer());
                }
            }
            responses.add(response);
            scopeUpdates.put(request.getQuestion().getScopeKey(), answer);
            outcome = coordinator.resumeAndRecord(request, Collections.unmodifiableList(responses),
                    Collections.unmodifiableMap(scopeUpdates));
        }
        requests.put(answered.getContext().getInteractionId(), answered);
        FeedbackResult result = new FeedbackResult(answered, false, outcome);
        feedbackByKey.put(feedbackKey, result);
        return result;
    }

    @Override
    public synchronized void cancel(String interactionId, String reason) {
        requireId(interactionId);
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        InteractionRequest request = requests.get(interactionId);
        if (request == null) {
            throw notFound();
        }
        if (request.getState() == InteractionStateEnum.CANCELLED) {
            return;
        }
        if (request.getState() != InteractionStateEnum.PENDING) {
            throw conflict();
        }
        coordinator.interactionCancelled(request, reason);
        requests.put(interactionId, new InteractionRequest(request.getContext(), request.getQuestion(),
                InteractionStateEnum.CANCELLED, null, reason));
    }

    private boolean hasOtherRequiredInteraction(InteractionRequest current) {
        for (InteractionRequest request : requests.values()) {
            if (!request.getContext().getInteractionId().equals(current.getContext().getInteractionId())
                    && request.getContext().getRunId().equals(current.getContext().getRunId())
                    && request.getContext().getInstanceId().equals(current.getContext().getInstanceId())
                    && request.getQuestion().isRequired()
                    && request.getState() != InteractionStateEnum.ANSWERED) {
                return true;
            }
        }
        return false;
    }

    private void expire(long now) {
        List<InteractionRequest> expired = new ArrayList<>();
        for (InteractionRequest request : requests.values()) {
            if (request.getState() == InteractionStateEnum.PENDING
                    && now >= request.getContext().getExpiresAtEpochMillis()) {
                coordinator.interactionCancelled(request, "expired");
                expired.add(new InteractionRequest(request.getContext(), request.getQuestion(),
                        InteractionStateEnum.EXPIRED, null, "expired"));
            }
        }
        for (InteractionRequest request : expired) {
            requests.put(request.getContext().getInteractionId(), request);
        }
    }

    private InteractionRequest expireRequest(InteractionRequest request) {
        coordinator.interactionCancelled(request, "expired");
        InteractionRequest expired = new InteractionRequest(request.getContext(), request.getQuestion(),
                InteractionStateEnum.EXPIRED, null, "expired");
        requests.put(expired.getContext().getInteractionId(), expired);
        return expired;
    }

    private Map<String, Object> effectiveAnswer(Question question, Map<String, Object> submitted) {
        Map<String, Object> values = submitted == null
                ? Collections.<String, Object>emptyMap()
                : submitted;
        if (values.isEmpty() && !question.getDefaultAnswer().isEmpty()) {
            return question.getDefaultAnswer();
        }
        return values;
    }

    private void validateAnswer(Question question, Map<String, Object> answer) {
        if (question.isRequired() && answer.isEmpty()) {
            invalidAnswer();
        }
        for (String key : answer.keySet()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if ((lower.contains("password") || lower.contains("token") || lower.contains("secret"))
                    && !"secretref".equals(lower)) {
                invalidAnswer();
            }
        }
        InteractionTypeEnum type = question.getType();
        if (type == InteractionTypeEnum.TEXT_INPUT) {
            Object text = answer.get("text");
            if (!(text instanceof String) || ((String) text).length() > question.getMaxTextLength()
                    || (question.isRequired() && ((String) text).trim().isEmpty())) {
                invalidAnswer();
            }
        } else if (type == InteractionTypeEnum.SINGLE_SELECT) {
            if (!validOption(question, answer.get("selectedOptionId"))) {
                invalidAnswer();
            }
        } else if (type == InteractionTypeEnum.MULTI_SELECT) {
            Object selected = answer.get("selectedOptionIds");
            if (!(selected instanceof Collection) || !validOptions(question, (Collection<?>) selected)) {
                invalidAnswer();
            }
        } else if (type == InteractionTypeEnum.CONFIRMATION) {
            Object decision = answer.get("decision");
            if (!("CONFIRM".equals(decision) || "REJECT".equals(decision) || "REPLAN".equals(decision))) {
                invalidAnswer();
            }
        } else if (type == InteractionTypeEnum.FILE) {
            if (!text(answer.get("fileRef"))) {
                invalidAnswer();
            }
        } else if (type == InteractionTypeEnum.ASSET_UPLOAD) {
            if (!text(answer.get("assetId"))) {
                invalidAnswer();
            }
        } else if (type == InteractionTypeEnum.CHANGE_APPROVAL) {
            if (!text(answer.get("changeSetId")) || !(answer.get("revision") instanceof Number)
                    || !text(answer.get("changeSetDigest")) || !text(answer.get("previewDigest"))
                    || !text(answer.get("decision"))) {
                invalidAnswer();
            }
        }
        if (!schemaValidator.isValid(question.getAnswerSchema(), answer)) {
            invalidAnswer();
        }
    }

    private static boolean validOption(Question question, Object selected) {
        if (!text(selected)) {
            return false;
        }
        for (InteractionOption option : question.getOptions()) {
            if (option.getOptionId().equals(selected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean validOptions(Question question, Collection<?> selected) {
        Set<Object> unique = new HashSet<>();
        if (selected.isEmpty()) {
            return !question.isRequired();
        }
        for (Object value : selected) {
            if (!unique.add(value) || !text(value)) {
                return false;
            }
            if (!question.getOptions().isEmpty() && !validOption(question, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean text(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty();
    }

    private static boolean sameIdentity(InteractionRequest request, InteractionContext context) {
        return request.getContext().getRunId().equals(context.getRunId())
                && request.getContext().getInstanceId().equals(context.getInstanceId())
                && request.getContext().getNodeOrTaskId().equals(context.getNodeOrTaskId())
                && request.getContext().getExpectedInstanceVersion() == context.getExpectedInstanceVersion();
    }

    private static void requireId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("identity must not be blank");
        }
    }

    private static void invalidAnswer() {
        throw InteractionErrors.error(ErrorCodes.INTERACTION_INVALID_ANSWER, ErrorCategory.VALIDATION);
    }

    private static RuntimeException conflict() {
        return InteractionErrors.error(ErrorCodes.INTERACTION_CONFLICT, ErrorCategory.CONFLICT);
    }

    private static RuntimeException notFound() {
        return InteractionErrors.error(ErrorCodes.INTERACTION_NOT_FOUND, ErrorCategory.VALIDATION);
    }

    private static RuntimeException denied() {
        return InteractionErrors.error(ErrorCodes.INTERACTION_ACCESS_DENIED, ErrorCategory.AUTHORIZATION);
    }
}
