package com.clara.planner;

public final class BudgetAmount {
    public static final BudgetAmount ZERO = new BudgetAmount(0L, 0L, 0L);
    private final long tokens;
    private final long costMicros;
    private final long workUnits;

    public BudgetAmount(long tokens, long costMicros, long workUnits) {
        if (tokens < 0L || costMicros < 0L || workUnits < 0L) {
            throw new IllegalArgumentException("budget values must not be negative");
        }
        this.tokens = tokens; this.costMicros = costMicros; this.workUnits = workUnits;
    }
    public long getTokens() { return tokens; }
    public long getCostMicros() { return costMicros; }
    public long getWorkUnits() { return workUnits; }
    public BudgetAmount plus(BudgetAmount other) {
        require(other); return new BudgetAmount(add(tokens, other.tokens),
                add(costMicros, other.costMicros), add(workUnits, other.workUnits));
    }
    public BudgetAmount minus(BudgetAmount other) {
        require(other);
        if (!covers(other)) throw new IllegalArgumentException("budget subtraction underflow");
        return new BudgetAmount(tokens - other.tokens, costMicros - other.costMicros,
                workUnits - other.workUnits);
    }
    public boolean covers(BudgetAmount other) {
        require(other); return tokens >= other.tokens && costMicros >= other.costMicros
                && workUnits >= other.workUnits;
    }
    private static void require(BudgetAmount value) {
        if (value == null) throw new IllegalArgumentException("budget must not be null");
    }
    private static long add(long one, long two) {
        if (Long.MAX_VALUE - one < two) throw new IllegalArgumentException("budget overflow");
        return one + two;
    }
    @Override public boolean equals(Object value) {
        if (!(value instanceof BudgetAmount)) return false;
        BudgetAmount other = (BudgetAmount) value;
        return tokens == other.tokens && costMicros == other.costMicros && workUnits == other.workUnits;
    }
    @Override public int hashCode() {
        int result = (int) (tokens ^ (tokens >>> 32));
        result = 31 * result + (int) (costMicros ^ (costMicros >>> 32));
        result = 31 * result + (int) (workUnits ^ (workUnits >>> 32)); return result;
    }
}
