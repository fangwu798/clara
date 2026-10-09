package com.clara.dto.planner;

/**
 * 预算快照：保存预算在特定时刻的限额、预留与已用额度。
 */
public final class BudgetSnapshot {

    private final BudgetAmount limit;
    private final BudgetAmount reserved;
    private final BudgetAmount used;

    /**
     * 创建预算快照。
     */
    public BudgetSnapshot(BudgetAmount limit, BudgetAmount reserved, BudgetAmount used) {
        this.limit = limit;
        this.reserved = reserved;
        this.used = used;
    }

    public BudgetAmount getLimit() {
        return limit;
    }

    public BudgetAmount getReserved() {
        return reserved;
    }

    public BudgetAmount getUsed() {
        return used;
    }
}
