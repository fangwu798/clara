package com.clara.orchestration.interaction;

import java.util.Map;

public interface AnswerSchemaValidator {
    boolean isValid(String schema, Map<String, Object> answer);
}
