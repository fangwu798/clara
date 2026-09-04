package com.clara.planner;

public final class BudgetSnapshot {
    private final BudgetAmount limit;
    private final BudgetAmount reserved;
    private final BudgetAmount used;
    BudgetSnapshot(BudgetAmount limit, BudgetAmount reserved, BudgetAmount used) {
        this.limit = limit; this.reserved = reserved; this.used = used;
    }
    public BudgetAmount getLimit() { return limit; }
    public BudgetAmount getReserved() { return reserved; }
    public BudgetAmount getUsed() { return used; }
}
