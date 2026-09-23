package com.clara.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具接口：一次可被模型调用的工具（描述信息 + 执行逻辑）。
 *
 * <p>是什么、在哪一层：契约层的工具抽象（包 {@code com.clara.tool}），把「工具声明（描述信息）」与
 * 「工具执行（invoke）」内聚到同一接口，并自带两种协议的声明渲染，使调用方声明工具时无需感知协议差异。
 *
 * <p>协议自渲染：{@link #toCompletionDeclaration()} 产出 Chat Completions 的嵌套 function 声明，
 * {@link #toResponseDeclaration()} 产出 Responses 的扁平 function 声明（含 {@code strict}），
 * 编码器直接调用即可，无需外部 {@code instanceof} 转换。
 *
 * <p>使用方法：
 * <pre>{@code
 * ITool tool = new MockTool("get_weather", "查询天气", schema);
 * request.setTools(Collections.singletonList(tool.toContext()));
 * // 模型调用后，按 name 匹配工具并 invoke 执行
 * String result = tool.invoke(arguments);
 * }</pre>
 *
 * <p>扩展：正式实现继承本接口（或实现类），通过扫描本地方法填充 {@code name/description/parameters}，
 * 并在 {@link #invoke(String)} 中真正调用本地方法、把返回结果类序列化为 JSON。
 *
 * <p>失败语义：本接口方法不抛异常约定；实现类 {@link #invoke(String)} 抛出的运行期异常由调用方处理。
 */
public interface ITool {

    /**
     * 工具名称：模型据此匹配要调用的函数，须唯一。
     *
     * @return 工具名，非 null
     */
    String name();

    /**
     * 工具功能描述：帮助模型理解何时以及如何调用。
     *
     * @return 描述文本，可为 null
     */
    String description();

    /**
     * 入参 JSON Schema：描述工具接受的参数结构。
     *
     * @return JSON Schema 对象，非 null
     */
    Map<String, Object> parameters();

    /**
     * 执行工具。
     *
     * @param argumentsJson 模型生成的入参 JSON 字符串，可能不是合法 JSON
     * @return 工具执行结果的 JSON 字符串
     */
    String invoke(String argumentsJson);

    /**
     * Chat Completions 协议：渲染为嵌套 function 声明。
     *
     * @return 线格式声明节点 {@code {"type":"function","function":{...}}}，非 null
     */
    default Map<String, Object> toCompletionDeclaration() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name());
        if (description() != null) {
            function.put("description", description());
        }
        function.put("parameters", parameters());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "function");
        item.put("function", function);
        return item;
    }

    /**
     * Responses 协议：渲染为扁平 function 声明（默认严格模式）。
     *
     * @return 线格式声明节点 {@code {"type":"function","name",...,"strict":true}}，非 null
     */
    default Map<String, Object> toResponseDeclaration() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "function");
        item.put("name", name());
        if (description() != null) {
            item.put("description", description());
        }
        item.put("parameters", parameters());
        item.put("strict", true);
        return item;
    }
}
