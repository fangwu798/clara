package com.clara.enums.interaction;

/**
 * 交互生命周期状态。
 */
public enum InteractionStateEnum {
    /** 待处理。 */
    PENDING,
    /** 已答。 */
    ANSWERED,
    /** 已取消。 */
    CANCELLED,
    /** 已过期。 */
    EXPIRED,
    /** 被替代。 */
    SUPERSEDED
}
