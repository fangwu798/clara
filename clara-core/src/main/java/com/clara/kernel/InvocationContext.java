package com.clara.kernel;

/**
 * 携带一次外部调用在各模块间传递的租户身份、业务关联、截止时间和取消信号。
 *
 * <p>常规用法：在应用入口为每次请求创建一个上下文，并随模型、媒体、MCP 或技能请求向下传递；
 * 子任务应通过 {@link #child} 派生上下文，从而共享租户、用户、跟踪 ID 和取消信号。
 *
 * <p>注意：上下文是不可变对象，但不会替调用方验证可选业务 ID 是否存在。租户 ID 和跟踪 ID 必填；
 * 子任务截止时间不得晚于父调用。长耗时操作应同时检查 {@link #remainingMillis} 和取消令牌。
 */
public final class InvocationContext {
    /**
     * 安全与数据隔离边界中的租户标识；必填，所有凭据解析和租户数据访问均以此值为准。
     */
    private final String tenantId;
    /**
     * 发起调用的用户或服务主体标识；没有用户维度的系统调用可以为空。
     */
    private final String userId;
    /**
     * 一次业务运行的关联标识；用于串联计划、技能和工作区操作，可在不需要运行维度时为空。
     */
    private final String runId;
    /**
     * 可暂停、恢复的业务实例标识；通常对应工作流或技能实例，不涉及实例状态时可以为空。
     */
    private final String instanceId;
    /**
     * 端到端观测使用的跟踪标识；必填，父子上下文保持相同值，不应用作业务幂等键。
     */
    private final String traceId;
    /**
     * 调用的绝对截止时间，单位为 Unix epoch 毫秒；当前时间达到或超过该值时剩余时间为零。
     */
    private final long deadlineEpochMillis;
    /**
     * 调用链共享的协作式取消令牌；取消父调用会同时影响由 {@link #child} 创建的所有子上下文。
     */
    private final CancellationToken cancellationToken;

    public InvocationContext(String tenantId, String userId, String runId,
            String traceId, long deadlineEpochMillis, CancellationToken cancellationToken) {
        this(tenantId, userId, runId, null, traceId, deadlineEpochMillis, cancellationToken);
    }

    public InvocationContext(String tenantId, String userId, String runId, String instanceId,
            String traceId, long deadlineEpochMillis, CancellationToken cancellationToken) {
        if (isBlank(tenantId)) { throw new IllegalArgumentException("tenantId must not be blank"); }
        if (isBlank(traceId)) { throw new IllegalArgumentException("traceId must not be blank"); }
        if (cancellationToken == null) { throw new IllegalArgumentException("cancellationToken must not be null"); }
        this.tenantId = tenantId;
        this.userId = userId;
        this.runId = runId;
        this.instanceId = instanceId;
        this.traceId = traceId;
        this.deadlineEpochMillis = deadlineEpochMillis;
        this.cancellationToken = cancellationToken;
    }
    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getRunId() { return runId; }
    public String getInstanceId() { return instanceId; }
    public String getTraceId() { return traceId; }
    public long getDeadlineEpochMillis() { return deadlineEpochMillis; }
    public CancellationToken getCancellationToken() { return cancellationToken; }
    /**
     * 计算指定时刻距离截止时间的剩余毫秒数，已到期时返回零。
     *
     * @param now 当前 Unix epoch 毫秒时间
     * @return 非负的剩余毫秒数
     */
    public long remainingMillis(long now) {
        return Math.max(0L, deadlineEpochMillis - now);
    }
    /**
     * 为子任务派生上下文，并复用当前租户、用户、跟踪 ID 和取消令牌。
     *
     * @param childRunId 子任务的业务运行标识，可以为空
     * @param childInstanceId 子任务的可恢复实例标识，可以为空
     * @param childDeadlineEpochMillis 子任务绝对截止时间，不得晚于父上下文
     * @return 新的子任务上下文
     */
    public InvocationContext child(String childRunId, String childInstanceId, long childDeadlineEpochMillis) {
        if (childDeadlineEpochMillis > deadlineEpochMillis) {
            throw new IllegalArgumentException("child deadline must not exceed parent deadline");
        }
        return new InvocationContext(tenantId, userId, childRunId, childInstanceId,
                traceId, childDeadlineEpochMillis, cancellationToken);
    }
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
