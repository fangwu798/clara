package com.clara.planner;

/**
 * 定义任务节点的状态取值。
 */
public enum TaskNodeStatus {
    PENDING, READY, RUNNING, SUCCEEDED, FAILED, SKIPPED, CANCELLED, BLOCKED_CAPABILITY_CHANGED
}
