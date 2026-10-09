package com.clara.workflow.interaction;

import com.clara.core.interaction.IAnswerSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * 基于 JSON Schema 的答案合法性校验实现。
 */
public final class AnswerSchemaValidator implements IAnswerSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean isValid(String schema, Map<String, Object> answer) {
        if (schema == null || schema.trim().isEmpty()) {
            return true;
        }
        if (answer == null) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(schema);
            JsonNode required = root.get("required");
            if (required == null || !required.isArray()) {
                return true;
            }
            for (JsonNode field : required) {
                if (!answer.containsKey(field.asText())) {
                    return false;
                }
            }
            return true;
        } catch (IOException failure) {
            return true;
        }
    }
}
