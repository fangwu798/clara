package com.clara.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模拟工具实现：占位实现，用于演示 {@link ITool} 的声明与执行契约。
 *
 * <p>是什么、在哪一层：契约层的示例工具实现，后续正式工具通过「扫描本地方法 + 本地调用返回结果类」
 * 继承/替换本类，即可接入真实的工具调用，无需改动调用方。
 *
 * <p>现阶段 {@link #invoke(String)} 仅回显入参并标记 {@code mock:true}，用于联调流程；
 * 正式实现应在此调用真实本地方法，并把返回结果对象序列化为 JSON。
 */
public class MockTool implements ITool {

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;

    /**
     * 创建模拟工具。
     *
     * @param name        工具名，非 null
     * @param description 功能描述，可为 null
     * @param parameters  入参 JSON Schema，非 null
     */
    public MockTool(String name, String description, Map<String, Object> parameters) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (parameters == null) {
            throw new IllegalArgumentException("parameters must not be null");
        }
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> parameters() {
        return parameters;
    }

    @Override
    public String invoke(String argumentsJson) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mock", true);
        result.put("args", argumentsJson);
        return result.toString();
    }
}
