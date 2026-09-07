package com.clara.workflow;

/**
 * 定义。
 */
public interface ScopeCondition {
    boolean test(ExecutionScope scope);
}
