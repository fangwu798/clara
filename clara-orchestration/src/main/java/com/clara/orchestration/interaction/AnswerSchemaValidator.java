package com.clara.orchestration.interaction;

import java.util.Map;

/**
 * 校验答案模式是否符合约束。
 */
public interface AnswerSchemaValidator {
    boolean isValid(String schema, Map<String, Object> answer);
}
