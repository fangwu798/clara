package com.clara.dto.planner;

import lombok.Data;

/**
 * 预算额度：以 token 数、成本微元与工作单元三个维度计量。
 */
@Data
public final class BudgetAmount {

    /** 零预算。 */
    public static final BudgetAmount ZERO = new BudgetAmount(0L, 0L, 0L);

    private final long tokens;
    private final long costMicros;
    private final long workUnits;

    /**
     * 创建预算额度。
     */
    public BudgetAmount(long tokens, long costMicros, long workUnits) {
        if (tokens < 0L || costMicros < 0L || workUnits < 0L) {
            throw new IllegalArgumentException("budget values must not be negative");
        }
        this.tokens = tokens;
        this.costMicros = costMicros;
        this.workUnits = workUnits;
    }



    public BudgetAmount plus(BudgetAmount other) {
        require(other);
        return new BudgetAmount(add(tokens, other.tokens), add(costMicros, other.costMicros), add(workUnits, other.workUnits));
    }

    public BudgetAmount minus(BudgetAmount other) {
        require(other);
        if (other.isExceededBy(this)) {
            throw new IllegalArgumentException("budget subtraction underflow");
        }
        return new BudgetAmount(tokens - other.tokens, costMicros - other.costMicros, workUnits - other.workUnits);
    }

    /**
     * 判断本额度是否已超出：计量维度更大即视为超出。
     *
     * @param other 待比较额度，不可为 null
     * @return 分量超出本额度时返回 true
     */
    public boolean isExceededBy(BudgetAmount other) {
        require(other);
        return other.tokens > tokens || other.costMicros > costMicros || other.workUnits > workUnits;
    }

    private static void require(BudgetAmount value) {
        if (value == null) {
            throw new IllegalArgumentException("budget must not be null");
        }
    }

    private static long add(long one, long two) {
        if (Long.MAX_VALUE - one < two) {
            throw new IllegalArgumentException("budget overflow");
        }
        return one + two;
    }

    @Override
    public boolean equals(Object value) {
        if (!(value instanceof BudgetAmount)) {
            return false;
        }
        BudgetAmount other = (BudgetAmount) value;
        return tokens == other.tokens && costMicros == other.costMicros && workUnits == other.workUnits;
    }

    @Override
    public int hashCode() {
        int result = (int) (tokens ^ (tokens >>> 32));
        result = 31 * result + (int) (costMicros ^ (costMicros >>> 32));
        result = 31 * result + (int) (workUnits ^ (workUnits >>> 32));
        return result;
    }
}
