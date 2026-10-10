package com.clara.dto.tool;

import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 函数签名：一个可被模型调用的函数的名称、功能描述与入参 JSON Schema。
 *
 * <p>是什么、在哪一层：契约层的请求值对象（包 {@code com.clara.dto.tool}），是 {@link ToolContext} 的
 * {@code function} 子对象，也是本项目中「函数声明」的唯一类型——请求侧不再有第二个同义类。
 *
 * <p>谁创建谁调用：由调用方在装配请求时构造（不可变值对象，构造期即校验），交给
 * {@link ToolContext} 后随请求体序列化下发；模型不会修改它。不需要 {@code close()}。
 *
 * <p>使用方法：
 * <pre>{@code
 * ToolSpecification weather = new ToolSpecification("get_weather", "查询指定城市的当前天气", schema);
 * request.setTools(Collections.singletonList(new ToolContext(weather)));
 * }</pre>
 *
 * <p>线格式：字段名与 JSON 一致，序列化为
 * {@code {"name":"...","description":"...","parameters":{...}}}，其中 {@code parameters} 为 JSON Schema 对象。
 *
 * <p>与响应侧的对应关系：本类描述「函数是什么」，模型决定调用后由 {@link ToolCallFunction} 回传
 * 「函数名 + 入参 JSON 字符串」——那个入参不保证符合本类声明的 Schema，调用方需自行校验。
 *
 * <p>失败语义：{@code name} 为 null 或空白、{@code parameters} 为 null 时构造抛
 * {@link IllegalArgumentException}，属调用方编程错误，不可重试；{@code description} 可为 null。
 */
@Data
public final class ToolSpecification {

    /**
     * 函数名：入参只允许 {@code a-z}、{@code A-Z}、{@code 0-9}、下划线与连字符，最长 128 个字符。
     */
    private final String name;

    /**
     * 功能描述，供模型理解何时以及如何调用该函数；可为 null。
     */
    private final String description;

    /**
     * 入参的 JSON Schema 对象，例如
     * {@code {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}。
     */
    private final Map<String, Object> parameters;

    /**
     * 创建函数签名。
     *
     * @param name        函数名，不可为 null 或空白
     * @param description 功能描述，可为 null
     * @param parameters  入参 JSON Schema，不可为 null；本类会拷贝为只读映射
     * @throws IllegalArgumentException name 或 parameters 不合法时
     */
    public ToolSpecification(String name, String description, Map<String, Object> parameters) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (parameters == null) {
            throw new IllegalArgumentException("parameters must not be null");
        }
        this.name = name;
        this.description = description;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
