package com.clara.skill.executor;

import com.clara.dto.skill.ToolMethod;
import com.clara.exception.ClaraException;
import com.clara.skill.SkillErrors;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
 *
 * <p>合法性约束：入参在调用工具方法前一律先做严格 JSON 解析（拒绝尾随内容与残缺结构），因此脚本
 * 不会收到无法解析的入参；仅当工具方法声明了 {@code parameters} 时再进一步做 Schema 匹配。
 */
public final class SkillSchemaValidator {

    /**
     * 严格 JSON 解析器：开启 {@code FAIL_ON_TRAILING_TOKENS}，使 {@code {"a":1} 尾随内容} 这类
     * 能被 Jackson 默认宽松放行、却会被下游脚本（如 Python {@code json}）拒绝的入参在调用前被拦下。
     */
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * 校验入参合法性：始终先做严格 JSON 解析，再在声明了 Schema 时做结构匹配。
     *
     * @param method        工具方法，不可为 null
     * @param argumentsJson 模型生成的入参 JSON，可为 null（视为空对象）
     * @throws ClaraException 入参不是合法 JSON 或不符合 Schema 时
     */
    public void validateInput(ToolMethod method, String argumentsJson) {
        JsonNode instance = parseArguments(argumentsJson);
        if (method.getParameters() == null || method.getParameters().trim().isEmpty()) {
            return;
        }
        JsonNode schema = parseSchema(method.getParameters());
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
        return  matches(schema, instance);
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

    /**
     * 严格解析工具方法入参：空入参视作空对象；解析失败或存在尾随内容时抛「入参不是合法 JSON」。
     *
     * @param argumentsJson 模型生成的入参 JSON，可为 null
     * @return 解析后的 JSON 节点，非 null
     * @throws ClaraException 入参不是合法 JSON 时
     */
    private JsonNode parseArguments(String argumentsJson) {
        String json = argumentsJson == null || argumentsJson.trim().isEmpty() ? "{}" : argumentsJson;
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw SkillErrors.schemaInvalid("工具方法入参不是合法 JSON：" + e.getMessage());
        }
    }

    private boolean matches(JsonNode schema, JsonNode instance) {
        if (schema == null || instance == null) {
            return false;
        }
        return matchesEnum(schema, instance)
                && matchesDeclaredType(schema, instance)
                && matchesObject(schema, instance)
                && matchesArray(schema, instance);
    }

    private static boolean matchesEnum(JsonNode schema, JsonNode instance) {
        JsonNode enumNode = schema.get("enum");
        if (enumNode == null || !enumNode.isArray()) {
            return true;
        }
        for (JsonNode option : enumNode) {
            if (option.equals(instance)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDeclaredType(JsonNode schema, JsonNode instance) {
        JsonNode typeNode = schema.get("type");
        if (typeNode == null) {
            return true;
        }
        if (typeNode.isTextual()) {
            return matchesType(typeNode.textValue(), instance);
        }
        return !typeNode.isArray() || matchesAnyType(typeNode, instance);
    }

    private boolean matchesAnyType(JsonNode typeNode, JsonNode instance) {
        for (JsonNode candidate : typeNode) {
            if (candidate.isTextual() && matchesType(candidate.textValue(), instance)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesObject(JsonNode schema, JsonNode instance) {
        if (!instance.isObject()) {
            return true;
        }
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return true;
        }
        return matchesProperties(instance, properties)
                && matchesRequired(schema, instance)
                && matchesAdditional(schema, instance, properties);
    }

    private boolean matchesProperties(JsonNode instance, JsonNode properties) {
        for (String name : fieldNames(instance)) {
            JsonNode childSchema = properties.get(name);
            if (childSchema != null && !matches(childSchema, instance.get(name))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesRequired(JsonNode schema, JsonNode instance) {
        JsonNode required = schema.get("required");
        if (required == null || !required.isArray()) {
            return true;
        }
        for (JsonNode field : required) {
            if (field.isTextual() && !instance.has(field.textValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAdditional(JsonNode schema, JsonNode instance, JsonNode properties) {
        JsonNode additional = schema.get("additionalProperties");
        if (additional == null || !additional.isBoolean() || additional.booleanValue()) {
            return true;
        }
        for (String name : fieldNames(instance)) {
            if (!properties.has(name)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesArray(JsonNode schema, JsonNode instance) {
        if (!instance.isArray()) {
            return true;
        }
        JsonNode items = schema.get("items");
        if (items == null) {
            return true;
        }
        for (JsonNode item : instance) {
            if (!matches(items, item)) {
                return false;
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
