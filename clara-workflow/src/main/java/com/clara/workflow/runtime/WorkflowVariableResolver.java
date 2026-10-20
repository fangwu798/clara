package com.clara.workflow.runtime;

import com.clara.dto.workflow.ExecutionScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流变量解析器：把动作参数模板中的 {@code ${...}} 占位符解析为执行作用域中的实际值。
 *
 * <p>是什么、在哪一层：clara-workflow 的无状态工具类（包 {@code com.clara.workflow.runtime}），
 * 供 AGENT / TOOL 动作在运行期解析提示词变量与工具参数。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code ${name}} 取 {@link ExecutionScope#get(String)}（顶层变量，即节点声明的输出键）；</li>
 *   <li>{@code ${name.field}} 在顶层变量为 Map（如 AGENT 节点解析出的 JSON 对象）时按字段逐级下钻；</li>
 *   <li>{@code ${name?}} 表示可选变量：解析不到时返回 null（字符串替换时替换为空串），用于
 *       可选输入（如视频生成的可选参考图）；不带 {@code ?} 时仍按失败语义抛异常；</li>
 *   <li>整个字符串恰为一个 {@code ${...}} 时返回原始对象（可能是 List / Map，供数组参数使用）；</li>
 *   <li>否则做字符串替换，非字符串对象用 Jackson 序列化为 JSON 文本。</li>
 * </ul>
 *
 * <p>失败语义：解析不到（值为 null）时抛 {@link IllegalArgumentException}（消息含表达式与节点 id），
 * 避免静默传空。
 */
public final class WorkflowVariableResolver {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern WHOLE_EXPRESSION = Pattern.compile("^\\$\\{([^}]+)}$");

    /** 可选变量后缀：表达式以它结尾时，变量缺失返回 null 而不是抛异常。 */
    private static final String OPTIONAL_SUFFIX = "?";

    private WorkflowVariableResolver() {
    }

    /**
     * 按模板类型递归解析值：字符串按占位符解析，Map / List 逐元素递归，其它类型原样返回。
     *
     * @param value  模板值，可为 null
     * @param scope  执行作用域，不可为 null
     * @param nodeId 当前节点 id（用于错误定位），可为 null
     * @return 解析后的值
     * @throws IllegalArgumentException 占位符解析不到时
     */
    public static Object resolveValue(Object value, ExecutionScope scope, String nodeId) {
        if (value instanceof String) {
            String text = (String) value;
            Matcher whole = WHOLE_EXPRESSION.matcher(text);
            if (whole.matches()) {
                return resolveExpression(whole.group(1), scope, nodeId);
            }
            return substitute(text, scope, nodeId);
        }
        if (value instanceof Map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                resolved.put(String.valueOf(entry.getKey()), resolveValue(entry.getValue(), scope, nodeId));
            }
            return resolved;
        }
        if (value instanceof List) {
            List<Object> resolved = new ArrayList<>();
            for (Object item : (List<?>) value) {
                resolved.add(resolveValue(item, scope, nodeId));
            }
            return resolved;
        }
        return value;
    }

    /**
     * 把值转为展示文本：字符串原样返回，其它对象用 Jackson 序列化为 JSON 文本。
     *
     * @param value 值，可为 null
     * @return 文本，非 null
     */
    public static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static String substitute(String template, ExecutionScope scope, String nodeId) {
        Matcher matcher = EXPRESSION.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            Object value = resolveExpression(matcher.group(1), scope, nodeId);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(stringify(value)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static Object resolveExpression(String expression, ExecutionScope scope, String nodeId) {
        String expr = expression == null ? "" : expression.trim();
        boolean optional = expr.endsWith(OPTIONAL_SUFFIX);
        if (optional) {
            expr = expr.substring(0, expr.length() - OPTIONAL_SUFFIX.length()).trim();
        }
        String[] segments = expr.split("\\.");
        Object value = segments.length == 0 ? null : scope.get(segments[0]);
        for (int index = 1; index < segments.length && value != null; index++) {
            value = value instanceof Map ? ((Map<?, ?>) value).get(segments[index]) : null;
        }
        if (value == null) {
            if (optional) {
                return null;
            }
            throw new IllegalArgumentException("工作流变量解析失败：${" + expr + "}（节点 " + nodeId + "）");
        }
        return value;
    }
}
