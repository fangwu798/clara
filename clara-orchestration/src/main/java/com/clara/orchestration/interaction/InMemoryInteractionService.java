package com.clara.orchestration.interaction;

import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;

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
 * 在内存中管理待处理的人机交互，并在收到反馈后触发业务恢复。
 *
 * <p>常规用法：执行流程需要人工输入时调用 {@link #request}，展示 {@link #pending} 返回的问题，
 * 再将用户反馈交给 {@link #submit}；取消业务时调用 {@link #cancel}。
 *
 * <p>注意：数据不会持久化，进程重启后交互会丢失，不适合多实例共享。提交时会校验租户、状态、过期时间、
 * 期望版本和答案 Schema；同一交互只允许完成一次，恢复协调器应具备业务幂等性。
 */
public final class InMemoryInteractionService implements InteractionService, UserInteractionGateway {
    private final InteractionResumeCoordinator coordinator;
    private final AnswerSchemaValidator schemaValidator;
    private final TimeSource timeSource;
    private final int maxInteractionsPerRun;
    private final Map<String, InteractionRequest> requests =
            new LinkedHashMap<String, InteractionRequest>();
    private final Map<String, FeedbackResult> feedbackByKey =
            new LinkedHashMap<String, FeedbackResult>();

    public InMemoryInteractionService(InteractionResumeCoordinator coordinator, AnswerSchemaValidator schemaValidator, TimeSource timeSource, int maxInteractionsPerRun) {
        if (coordinator == null || schemaValidator == null || timeSource == null || maxInteractionsPerRun <= 0) {
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
            if (sameIdentity(existing, context)) return existing;
            throw conflict();
        }
        int count = 0;
        for (InteractionRequest value : requests.values()) {
            if (value.getTenantId().equals(context.getTenantId())
                    && value.getRunId().equals(context.getRunId())) count++;
        }
        if (count >= maxInteractionsPerRun) {
            throw InteractionErrors.error(ErrorCodes.INTERACTION_LIMIT_EXCEEDED, ErrorCategory.RESOURCE_LIMIT);
        }
        InteractionRequest created = new InteractionRequest(context, question,
                InteractionState.PENDING, null, null);
        requests.put(created.getInteractionId(), created);
        return created;
    }

    public synchronized List<InteractionRequest> pending(String tenantId, String runId) {
        requireId(tenantId);
        requireId(runId);
        expire(timeSource.currentTimeMillis());
        List<InteractionRequest> result = new ArrayList<InteractionRequest>();
        for (InteractionRequest request : requests.values()) {
            if (request.getTenantId().equals(tenantId) && request.getRunId().equals(runId)
                    && request.getState() == InteractionState.PENDING) result.add(request);
        }
        Collections.sort(result, new Comparator<InteractionRequest>() {
            public int compare(InteractionRequest one, InteractionRequest two) {
                int time = Long.compare(one.getCreatedAtEpochMillis(), two.getCreatedAtEpochMillis());
                return time != 0 ? time : one.getInteractionId().compareTo(two.getInteractionId());
            }
        });
        return Collections.unmodifiableList(result);
    }

    public synchronized InteractionRequest get(String tenantId, String runId, String interactionId) {
        requireId(tenantId);
        requireId(runId);
        requireId(interactionId);
        expire(timeSource.currentTimeMillis());
        InteractionRequest request = requests.get(interactionId);
        if (request == null) {
            throw notFound();
        }
        authorize(request, tenantId, runId);
        return request;
    }

    public synchronized FeedbackResult submit(InteractionFeedback feedback) {
        if (feedback == null) {
            throw new IllegalArgumentException("feedback must not be null");
        }
        String feedbackKey = feedback.getTenantId() + ":" + feedback.getRunId()
                + ":" + feedback.getIdempotencyKey();
        FeedbackResult replay = feedbackByKey.get(feedbackKey);
        if (replay != null) {
            if (!replay.getRequest().getInteractionId().equals(feedback.getInteractionId())) {
                throw conflict();
            }
            return replay.asReplay();
        }
        InteractionRequest request = requests.get(feedback.getInteractionId());
        if (request == null) {
            throw notFound();
        }
        authorize(request, feedback.getTenantId(), feedback.getRunId());
        long now = timeSource.currentTimeMillis();
        if (request.getState() == InteractionState.PENDING && now >= request.getExpiresAtEpochMillis()) {
            request = expireRequest(request);
        }
        if (request.getState() == InteractionState.EXPIRED) {
            throw InteractionErrors.error(ErrorCodes.INTERACTION_EXPIRED, ErrorCategory.CONFLICT);
        }
        if (request.getState() != InteractionState.PENDING
                || feedback.getExpectedInstanceVersion() != request.getExpectedInstanceVersion()) {
            throw conflict();
        }
        Map<String, Object> answer = effectiveAnswer(request.getQuestion(), feedback.getAnswer());
        validateAnswer(request.getQuestion(), answer);
        InteractionResponse response = new InteractionResponse(request.getInteractionId(), answer,
                feedback.getResponderId(), feedback.getIdempotencyKey(), now);
        InteractionRequest answered = request.transition(InteractionState.ANSWERED, response, null);
        ResumeOutcome outcome = null;
        if (!hasOtherRequiredInteraction(request)) {
            Map<String, Object> scopeUpdates = new LinkedHashMap<String, Object>();
            List<InteractionResponse> responses = new ArrayList<InteractionResponse>();
            for (InteractionRequest value : requests.values()) {
                if (value.getTenantId().equals(request.getTenantId())
                        && value.getRunId().equals(request.getRunId())
                        && value.getInstanceId().equals(request.getInstanceId())
                        && value.getState() == InteractionState.ANSWERED) {
                    responses.add(value.getResponse());
                    scopeUpdates.put(value.getQuestion().getScopeKey(), value.getResponse().getAnswer());
                }
            }
            responses.add(response);
            scopeUpdates.put(request.getQuestion().getScopeKey(), answer);
            outcome = coordinator.resumeAndRecord(request,
                    Collections.unmodifiableList(responses), Collections.unmodifiableMap(scopeUpdates));
        }
        requests.put(answered.getInteractionId(), answered);
        FeedbackResult result = new FeedbackResult(answered, false, outcome);
        feedbackByKey.put(feedbackKey, result);
        return result;
    }

    public synchronized void cancel(String tenantId, String interactionId, String reason) {
        requireId(tenantId);
        requireId(interactionId);
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        InteractionRequest request = requests.get(interactionId);
        if (request == null) {
            throw notFound();
        }
        if (!request.getTenantId().equals(tenantId)) {
            throw denied();
        }
        if (request.getState() == InteractionState.CANCELLED) return;
        if (request.getState() != InteractionState.PENDING) {
            throw conflict();
        }
        coordinator.interactionCancelled(request, reason);
        requests.put(interactionId, request.transition(InteractionState.CANCELLED, null, reason));
    }

    private boolean hasOtherRequiredInteraction(InteractionRequest current) {
        for (InteractionRequest request : requests.values()) {
            if (!request.getInteractionId().equals(current.getInteractionId())
                    && request.getTenantId().equals(current.getTenantId())
                    && request.getRunId().equals(current.getRunId())
                    && request.getInstanceId().equals(current.getInstanceId())
                    && request.getQuestion().isRequired()
                    && request.getState() != InteractionState.ANSWERED) return true;
        }
        return false;
    }

    private void expire(long now) {
        List<InteractionRequest> expired = new ArrayList<InteractionRequest>();
        for (InteractionRequest request : requests.values()) {
            if (request.getState() == InteractionState.PENDING && now >= request.getExpiresAtEpochMillis()) {
                coordinator.interactionCancelled(request, "expired");
                expired.add(request.transition(InteractionState.EXPIRED, null, "expired"));
            }
        }
        for (InteractionRequest request : expired) requests.put(request.getInteractionId(), request);
    }

    private InteractionRequest expireRequest(InteractionRequest request) {
        coordinator.interactionCancelled(request, "expired");
        InteractionRequest expired = request.transition(InteractionState.EXPIRED, null, "expired");
        requests.put(expired.getInteractionId(), expired);
        return expired;
    }

    private Map<String, Object> effectiveAnswer(Question question, Map<String, Object> submitted) {
        if (submitted.isEmpty() && !question.getDefaultAnswer().isEmpty()) return question.getDefaultAnswer();
        return submitted;
    }

    private void validateAnswer(Question question, Map<String, Object> answer) {
        if (question.isRequired() && answer.isEmpty()) invalidAnswer();
        for (String key : answer.keySet()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if ((lower.contains("password") || lower.contains("token") || lower.contains("secret"))
                    && !"secretref".equals(lower)) invalidAnswer();
        }
        InteractionType type = question.getType();
        if (type == InteractionType.TEXT_INPUT) {
            Object text = answer.get("text");
            if (!(text instanceof String) || ((String) text).length() > question.getMaxTextLength()
                    || (question.isRequired() && ((String) text).trim().isEmpty())) invalidAnswer();
        } else if (type == InteractionType.SINGLE_SELECT) {
            if (!validOption(question, answer.get("selectedOptionId"))) invalidAnswer();
        } else if (type == InteractionType.MULTI_SELECT || type == InteractionType.FILE_SELECTION) {
            Object selected = answer.get("selectedOptionIds");
            if (!(selected instanceof Collection) || !validOptions(question, (Collection<?>) selected)) invalidAnswer();
        } else if (type == InteractionType.CONFIRMATION) {
            Object decision = answer.get("decision");
            if (!("CONFIRM".equals(decision) || "REJECT".equals(decision) || "REPLAN".equals(decision)))
                invalidAnswer();
        } else if (type == InteractionType.FILE_UPLOAD) {
            if (!text(answer.get("fileRef")) || !text(answer.get("sha256"))) invalidAnswer();
        } else if (type == InteractionType.ASSET_UPLOAD) {
            if (!text(answer.get("assetId"))) invalidAnswer();
        } else if (type == InteractionType.CHANGE_APPROVAL) {
            if (!text(answer.get("changeSetId")) || !(answer.get("revision") instanceof Number)
                    || !text(answer.get("changeSetDigest")) || !text(answer.get("previewDigest"))
                    || !text(answer.get("decision"))) invalidAnswer();
        }
        if (!schemaValidator.isValid(question.getAnswerSchema(), answer)) invalidAnswer();
    }

    private static boolean validOption(Question question, Object selected) {
        if (!text(selected)) return false;
        for (InteractionOption option : question.getOptions()) {
            if (option.getOptionId().equals(selected)) return true;
        }
        return false;
    }

    private static boolean validOptions(Question question, Collection<?> selected) {
        Set<Object> unique = new HashSet<Object>();
        if (selected.isEmpty()) return !question.isRequired();
        for (Object value : selected) {
            if (!unique.add(value) || !text(value)) return false;
            if (!question.getOptions().isEmpty() && !validOption(question, value)) return false;
        }
        return true;
    }

    private static boolean text(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty();
    }

    private static boolean sameIdentity(InteractionRequest request, InteractionContext context) {
        return request.getTenantId().equals(context.getTenantId())
                && request.getRunId().equals(context.getRunId())
                && request.getInstanceId().equals(context.getInstanceId())
                && request.getNodeOrTaskId().equals(context.getNodeOrTaskId())
                && request.getExpectedInstanceVersion() == context.getExpectedInstanceVersion();
    }

    private static void authorize(InteractionRequest request, String tenantId, String runId) {
        if (!request.getTenantId().equals(tenantId) || !request.getRunId().equals(runId)) {
            throw denied();
        }
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
