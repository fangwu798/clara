package com.clara.orchestration.api;

/**
 * 编排终态：一次对话编排结束后的结果状态。
 */
public enum OrchestrationStatusEnum {
    /** 正常完成：模型给出最终回复且无待处理工具调用。 */
    COMPLETED,
    /** 已取消：编排过程中会话取消令牌被触发。 */
    CANCELLED,
    /** 失败：编排过程中发生不可恢复错误。 */
    FAILED
}
