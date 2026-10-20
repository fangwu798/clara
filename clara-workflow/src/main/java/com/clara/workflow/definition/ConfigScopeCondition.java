package com.clara.workflow.definition;

import com.clara.core.workflow.IScopeCondition;
import com.clara.dto.workflow.ExecutionScope;

import java.util.Objects;

/** 配置化条件节点使用的简单作用域条件。 */
public final class ConfigScopeCondition implements IScopeCondition {

    private final String key;
    private final String operator;
    private final String expected;

    public ConfigScopeCondition(String key, String operator, String expected) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("condition key must not be blank");
        }
        this.key = key.trim();
        this.operator = operator == null || operator.trim().isEmpty() ? "equals" : operator.trim();
        this.expected = expected;
    }

    @Override
    public boolean test(ExecutionScope scope) {
        Object value = scope == null ? null : scope.get(key);
        if ("exists".equalsIgnoreCase(operator)) {
            return value != null;
        }
        String actual = value == null ? null : String.valueOf(value);
        boolean equal = Objects.equals(expected, actual);
        return "notEquals".equalsIgnoreCase(operator) != equal;
    }
}
