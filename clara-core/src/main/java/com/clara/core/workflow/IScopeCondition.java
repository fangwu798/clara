package com.clara.core.workflow;

import com.clara.dto.workflow.ExecutionScope;

/**
 * 作用域条件：基于执行作用域求值一个布尔条件，用于条件分支与循环退出判断。
 */
public interface IScopeCondition {

    /**
     * 判断条件是否成立。
     *
     * @param scope 执行作用域快照
     * @return 条件成立时返回 {@code true}
     */
    boolean test(ExecutionScope scope);
}
