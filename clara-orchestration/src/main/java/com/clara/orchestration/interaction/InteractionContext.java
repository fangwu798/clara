package com.clara.orchestration.interaction;

/**
 * 描述一次人工交互与租户、业务运行、可恢复实例及触发节点之间的关联关系。
 *
 * <p>常规用法：工作流或任务进入等待输入状态时创建该上下文，再与问题一起传给
 * {@link InteractionService#request}；后续反馈必须携带相同的租户、运行、交互 ID 和期望实例版本。
 *
 * <p>注意：所有标识均必填，时间字段使用 Unix epoch 毫秒。过期时间必须晚于创建时间；
 * {@code expectedInstanceVersion} 用于拒绝针对旧检查点的迟到或重复反馈，不能用当前时间或尝试次数代替。
 */
public final class InteractionContext {
    /**
     * 本次交互的全局标识，用于查询、提交反馈和幂等地复用同一交互请求。
     */
    private final String interactionId;
    /**
     * 交互所属租户标识，是读取问题和提交反馈时的授权隔离边界。
     */
    private final String tenantId;
    /**
     * 产生交互的业务运行标识，用于列出同一次运行中的全部待处理交互。
     */
    private final String runId;
    /**
     * 等待反馈的工作流或任务实例标识，反馈完成或取消后据此恢复或取消实例。
     */
    private final String instanceId;
    /**
     * 触发交互的工作流节点或计划任务标识，用于定位等待点和写回答案。
     */
    private final String nodeOrTaskId;
    /**
     * 创建交互时实例检查点的版本号，提交反馈时必须精确匹配以实现乐观并发控制。
     */
    private final long expectedInstanceVersion;
    /**
     * 交互创建时间，单位为 Unix epoch 毫秒，用于排序和审计。
     */
    private final long createdAtEpochMillis;
    /**
     * 交互失效时间，单位为 Unix epoch 毫秒；当前时间达到该值时交互不可再提交。
     */
    private final long expiresAtEpochMillis;
    public InteractionContext(String interactionId, String tenantId, String runId, String instanceId,
            String nodeOrTaskId, long expectedInstanceVersion, long createdAtEpochMillis,
            long expiresAtEpochMillis) {
        if (blank(interactionId) || blank(tenantId) || blank(runId) || blank(instanceId)
                || blank(nodeOrTaskId) || expectedInstanceVersion < 0L
                || createdAtEpochMillis < 0L || expiresAtEpochMillis <= createdAtEpochMillis) {
            throw new IllegalArgumentException("interaction context is invalid");
        }
        this.interactionId = interactionId; this.tenantId = tenantId; this.runId = runId;
        this.instanceId = instanceId; this.nodeOrTaskId = nodeOrTaskId;
        this.expectedInstanceVersion = expectedInstanceVersion;
        this.createdAtEpochMillis = createdAtEpochMillis; this.expiresAtEpochMillis = expiresAtEpochMillis;
    }
    public String getInteractionId() { return interactionId; }
    public String getTenantId() { return tenantId; }
    public String getRunId() { return runId; }
    public String getInstanceId() { return instanceId; }
    public String getNodeOrTaskId() { return nodeOrTaskId; }
    public long getExpectedInstanceVersion() { return expectedInstanceVersion; }
    public long getCreatedAtEpochMillis() { return createdAtEpochMillis; }
    public long getExpiresAtEpochMillis() { return expiresAtEpochMillis; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
