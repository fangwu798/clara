package com.clara.dto.interaction;

import com.clara.utils.StringUtils;
import lombok.Data;

/**
 * 交互上下文：描述一次交互与运行、可恢复实例及触发节点之间的关联信息。
 *
 * <p>无租户：不含 {@code tenantId}。所有标识均必填，时间字段使用 Unix epoch 毫秒；过期时间必须晚于创建时间；
 * {@code expectedInstanceVersion} 用于拒绝针对旧检查点的迟到或重复反馈。
 */
@Data
public final class InteractionContext {

    private final String interactionId;
    private final String runId;
    private final String instanceId;
    private final String nodeOrTaskId;
    private final long expectedInstanceVersion;
    private final long createdAtEpochMillis;
    private final long expiresAtEpochMillis;

    /**
     * 创建交互上下文。
     *
     * @param interactionId           交互标识，不可为空白
     * @param runId                   运行标识，不可为空白
     * @param instanceId              等待反馈的工作流 / 任务实例标识，不可为空白
     * @param nodeOrTaskId            触发交互的节点或任务标识，不可为空白
     * @param expectedInstanceVersion 创建交互时实例检查点版本，不可为负
     * @param createdAtEpochMillis    创建时间（Unix epoch 毫秒），不可为负
     * @param expiresAtEpochMillis    失效时间（Unix epoch 毫秒），必须晚于创建时间
     * @throws IllegalArgumentException 任意字段不合法时
     */
    public InteractionContext(String interactionId, String runId, String instanceId,
            String nodeOrTaskId, long expectedInstanceVersion, long createdAtEpochMillis,
            long expiresAtEpochMillis) {
        if (StringUtils.isBlank(interactionId) || StringUtils.isBlank(runId) || StringUtils.isBlank(instanceId) || StringUtils.isBlank(nodeOrTaskId)
                || expectedInstanceVersion < 0L || createdAtEpochMillis < 0L
                || expiresAtEpochMillis <= createdAtEpochMillis) {
            throw new IllegalArgumentException("interaction context is invalid");
        }
        this.interactionId = interactionId;
        this.runId = runId;
        this.instanceId = instanceId;
        this.nodeOrTaskId = nodeOrTaskId;
        this.expectedInstanceVersion = expectedInstanceVersion;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

}
