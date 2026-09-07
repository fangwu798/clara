package com.clara.orchestration.interaction;

/**
 * 封装反馈操作的执行结果。
 */
public final class FeedbackResult {
    private final InteractionRequest request;
    private final boolean idempotentReplay;
    private final ResumeOutcome resumeOutcome;
    FeedbackResult(InteractionRequest request, boolean replay, ResumeOutcome resumeOutcome) {
        this.request = request; this.idempotentReplay = replay; this.resumeOutcome = resumeOutcome;
    }
    public InteractionRequest getRequest() { return request; }
    public boolean isIdempotentReplay() { return idempotentReplay; }
    public boolean isResumed() { return resumeOutcome != null; }
    public ResumeOutcome getResumeOutcome() { return resumeOutcome; }
    FeedbackResult asReplay() { return new FeedbackResult(request, true, resumeOutcome); }
}
