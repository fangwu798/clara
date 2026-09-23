package com.clara.tool;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具声明条目：告诉模型「有哪些工具可用」，是 {@code ModelParameters.tools} 的元素。
 *
 * <p>是什么、在哪一层：契约层的请求值对象（包 {@code com.clara.tool}），是请求体 {@code tools[]} 的元素
 * ——线格式为 {@code {"type":"function","function":{...}}}，函数签名统一由 {@link ToolSpecification} 承载。
 *
 * <p>与响应侧的对应关系：本类描述「工具是什么」（函数名、功能描述、JSON Schema 入参），
 * 响应侧的 {@link ToolCall} 描述「模型要调用哪个工具、传什么参数」（调用 ID 与入参 JSON 字符串），
 * 两者形状不同、不共用类型。
 *
 * <p>使用方法：
 * <pre>{@code
 * ToolSpecification weather = new ToolSpecification("get_weather", "查询指定城市的当前天气", schema);
 * request.setTools(Collections.singletonList(new ToolContext(weather)));
 *
 * // 图片生成的联网搜索：只有类型，不带函数签名
 * request.setTools(Collections.singletonList(new ToolContext("web_search", null)));
 * }</pre>
 *
 * <p>失败语义：本类不主动校验；{@link #getFunction()} 为 null 时的合法性取决于使用场景——
 * {@code type = function} 时必须非 null，{@code type = web_search} 时该字段不下发。
 */
@Getter
public class ToolContext {

    /**
     * tool 的类型，默认 {@code function}。
     *
     * <p>取值随使用类型不同：文本对话为 {@code function}（配套 {@link ToolSpecification} 描述函数签名）；
     * 图片生成的联网搜索为 {@code web_search}（形如 {@code {"type":"web_search"}}，此时不需要
     * {@link #function}，由模型自主决定是否搜索互联网内容）。
     */
    @Setter
    private String type = "function";

    /**
     * 对应的函数签名；{@code type = web_search} 时为 null。
     */
    private final ToolSpecification function;

    /**
     * 创建函数工具声明，{@link #type} 取默认值 {@code function}。
     *
     * @param function 函数签名，不可为 null
     */
    public ToolContext(ToolSpecification function) {
        this.function = function;
    }

    /**
     * 创建指定类型的工具声明。
     *
     * @param type     工具类型，如 {@code web_search}；为 null 时取默认值 {@code function}
     * @param function 函数签名；{@code web_search} 等无需函数签名的类型传 null
     */
    public ToolContext(String type, ToolSpecification function) {
        if (type != null) {
            this.type = type;
        }
        this.function = function;
    }

    /**
     * Chat Completions 协议：渲染为嵌套 function 声明。
     *
     * @return 线格式声明节点 {@code {"type":"function","function":{...}}}，非 null
     */
    public Map<String, Object> toCompletionTool() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        if (function != null) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", function.getName());
            if (function.getDescription() != null) {
                fn.put("description", function.getDescription());
            }
            fn.put("parameters", function.getParameters());
            item.put("function", fn);
        }
        return item;
    }

    /**
     * Responses 协议：渲染为扁平 function 声明（默认严格模式）。
     *
     * @return 线格式声明节点 {@code {"type":"function","name",...,"strict":true}}，非 null
     */
    public Map<String, Object> toResponseTool() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        if (function != null) {
            item.put("name", function.getName());
            if (function.getDescription() != null) {
                item.put("description", function.getDescription());
            }
            item.put("parameters", function.getParameters());
            item.put("strict", true);
        }
        return item;
    }
}
