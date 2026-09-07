package com.clara.planner;

/**
 * 定义计划的状态取值。
 */
public enum PlanStatus {
    DRAFT, VALIDATED, RUNNING, WAITING_INPUT, REPLANNING, SUCCEEDED, FAILED, CANCELLED
}
