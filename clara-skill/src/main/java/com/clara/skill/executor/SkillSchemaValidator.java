package com.clara.skill.executor;

import com.clara.dto.skill.ToolMethod;
import com.clara.exception.ClaraException;
import com.clara.skill.SkillErrors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 输入 / 输出 Schema 校验器。
 *
 * <p>执行前校验入参是否符合工具方法的 {@code parameters} JSON Schema；执行后校验输出为合法 JSON。
 * 支持 {@code type / properties / required / items / enum / additionalProperties} 等常见关键字的子集，
 * 满足技能级输入输出合法性校验需求。
 */
public final class SkillSchemaValidator {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 校验入参 JSON 是否符合工具方法声明的参数 Schema；未声明 Schema 时跳过。
     *
     * @param method        工具方法，不可为 null
     * @param argumentsJson 模型生成的入参 JSON，可为 null
     * @throws ClaraException 入参不符合 Schema 时
     */
    public void validateInput(ToolMethod method, String argumentsJson) {
        if (method.getParameters() == null || method.getParameters().trim().isEmpty()) {
            return;
        }
        JsonNode schema = parseSchema(method.getParameters());
        String json = argumentsJson == null || argumentsJson.trim().isEmpty() ? "{}" : argumentsJson;
        JsonNode instance = parseInstance(json);
        if (!matches(schema, instance)) {
            throw SkillErrors.schemaInvalid("入参不符合工具方法 " + method.getName() + " 的参数 Schema");
        }
    }

    /**
     * 校验脚本输出是否为合法 JSON。
     *
     * @param method    工具方法，不可为 null
     * @param resultJson 脚本 stdout 结果，可为 null
     * @throws ClaraException 输出不是合法 JSON 时
     */
    public void validateOutput(ToolMethod method, String resultJson) {
        JsonNode instance = parseInstance(resultJson);
        if (instance == null) {
            throw SkillErrors.schemaInvalid("工具方法 " + method.getName() + " 的输出不是合法 JSON");
        }
    }

    /**
     * 判断实例 JSON 是否符合给定的 JSON Schema（供校验与测试复用）。
     *
     * @param schemaJson   JSON Schema 文本，不可为 null
     * @param instanceJson 待校验 JSON 文本，不可为 null
     * @return 符合时为 true，Schema 或实例无法解析时返回 false
     */
    public boolean isValid(String schemaJson, String instanceJson) {
        JsonNode schema = parseSchema(schemaJson);
        JsonNode instance = parseInstance(instanceJson);
        return schema != null && instance != null && matches(schema, instance);
    }

    private JsonNode parseSchema(String schemaJson) {
        try {
            return mapper.readTree(schemaJson);
        } catch (IOException e) {
            throw SkillErrors.schemaInvalid("参数 Schema 不是合法 JSON");
        }
    }

    private JsonNode parseInstance(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean matches(JsonNode schema, JsonNode instance) {
        if (schema == null || instance == null) {
            return false;
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            boolean found = false;
            for (JsonNode option : enumNode) {
                if (option.equals(instance)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode != null) {
            if (typeNode.isTextual()) {
                if (!matchesType(typeNode.textValue(), instance)) {
                    return false;
                }
            } else if (typeNode.isArray()) {
                boolean any = false;
                for (JsonNode candidate : typeNode) {
                    if (candidate.isTextual() && matchesType(candidate.textValue(), instance)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    return false;
                }
            }
        }

        if (instance.isObject()) {
            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                for (String name : fieldNames(instance)) {
                    JsonNode childSchema = properties.get(name);
                    if (childSchema != null && !matches(childSchema, instance.get(name))) {
                        return false;
                    }
                }
                JsonNode required = schema.get("required");
                if (required != null && required.isArray()) {
                    for (JsonNode field : required) {
                        if (field.isTextual() && !instance.has(field.textValue())) {
                            return false;
                        }
                    }
                }
                JsonNode additional = schema.get("additionalProperties");
                if (additional != null && additional.isBoolean() && !additional.booleanValue()) {
                    for (String name : fieldNames(instance)) {
                        if (!properties.has(name)) {
                            return false;
                        }
                    }
                }
            }
        }

        if (instance.isArray()) {
            JsonNode items = schema.get("items");
            if (items != null) {
                for (JsonNode item : instance) {
                    if (!matches(items, item)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean matchesType(String type, JsonNode node) {
        if (node == null) {
            return "null".equals(type);
        }
        if ("object".equals(type)) {
            return node.isObject();
        }
        if ("array".equals(type)) {
            return node.isArray();
        }
        if ("string".equals(type)) {
            return node.isTextual();
        }
        if ("boolean".equals(type)) {
            return node.isBoolean();
        }
        if ("number".equals(type)) {
            return node.isNumber();
        }
        if ("integer".equals(type)) {
            return node.isIntegralNumber();
        }
        if ("null".equals(type)) {
            return node.isNull();
        }
        return true;
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
