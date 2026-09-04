package com.clara.orchestration.interaction;

public final class InteractionRequest {
    private static final int SCHEMA_VERSION = 1;
    private final InteractionContext context;
    private final Question question;
    private final InteractionState state;
    private final InteractionResponse response;
    private final String terminalReason;
    InteractionRequest(InteractionContext context, Question question, InteractionState state,
            InteractionResponse response, String terminalReason) {
        if (context == null || question == null || state == null) {
            throw new IllegalArgumentException("request fields are invalid");
        }
        this.context = context; this.question = question; this.state = state;
        this.response = response; this.terminalReason = terminalReason;
    }
    public int getSchemaVersion() { return SCHEMA_VERSION; }
    public String getInteractionId() { return context.getInteractionId(); }
    public String getTenantId() { return context.getTenantId(); }
    public String getRunId() { return context.getRunId(); }
    public String getInstanceId() { return context.getInstanceId(); }
    public String getNodeOrTaskId() { return context.getNodeOrTaskId(); }
    public long getExpectedInstanceVersion() { return context.getExpectedInstanceVersion(); }
    public long getCreatedAtEpochMillis() { return context.getCreatedAtEpochMillis(); }
    public long getExpiresAtEpochMillis() { return context.getExpiresAtEpochMillis(); }
    public Question getQuestion() { return question; }
    public InteractionState getState() { return state; }
    public InteractionResponse getResponse() { return response; }
    public String getTerminalReason() { return terminalReason; }
    InteractionRequest transition(InteractionState value, InteractionResponse answer, String reason) {
        return new InteractionRequest(context, question, value, answer, reason);
    }
}
