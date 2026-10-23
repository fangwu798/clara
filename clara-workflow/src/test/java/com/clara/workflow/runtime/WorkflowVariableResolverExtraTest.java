package com.clara.workflow.runtime;

import com.clara.dto.workflow.ExecutionScope;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 补充覆盖 {@link WorkflowVariableResolver} 的递归解析与文本替换语义。
 *
 * <p>工作流参数通常是「Map / List 里嵌套占位符」，只要递归少走一层就会出现
 * 参数静默不变的问题，因此按容器类型逐一验证。
 */
public class WorkflowVariableResolverExtraTest {

    private ExecutionScope scope() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("name", "clara");
        inputs.put("count", 3);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", 42);
        user.put("profile", Collections.singletonMap("city", "上海"));
        inputs.put("user", user);
        inputs.put("tags", Arrays.asList("a", "b"));
        return new ExecutionScope(inputs);
    }

    @Test
    public void resolvesNestedMapAndListRecursively() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("greeting", "hi ${name}");
        template.put("ids", Arrays.asList("${user.id}", "${count}"));
        template.put("nested", Collections.singletonMap("city", "${user.profile.city}"));

        Object resolved = WorkflowVariableResolver.resolveValue(template, scope(), "node-1");

        Map<?, ?> map = (Map<?, ?>) resolved;
        assertEquals("hi clara", map.get("greeting"));
        assertEquals(Arrays.asList(42, 3), map.get("ids"));
        assertEquals("上海", ((Map<?, ?>) map.get("nested")).get("city"));
    }

    @Test
    public void resolvesWholeExpressionToOriginalObject() {
        Object tags = WorkflowVariableResolver.resolveValue("${tags}", scope(), "node-1");
        assertTrue(tags instanceof List);
        assertEquals(Arrays.asList("a", "b"), tags);

        Object user = WorkflowVariableResolver.resolveValue("${user}", scope(), "node-1");
        assertTrue(user instanceof Map);
        assertEquals(42, ((Map<?, ?>) user).get("id"));
    }

    @Test
    public void leavesNonStringValuesAsIs() {
        Object value = new Object();
        assertSame(value, WorkflowVariableResolver.resolveValue(value, scope(), "node-1"));
        assertNull(WorkflowVariableResolver.resolveValue(null, scope(), "node-1"));
        assertEquals(3, WorkflowVariableResolver.resolveValue(3, scope(), "node-1"));
    }

    @Test
    public void substitutesMultiplePlaceholdersInOneString() {
        assertEquals("clara-42-3",
                WorkflowVariableResolver.resolveValue("${name}-${user.id}-${count}", scope(), "node-1"));
    }

    @Test
    public void nestedFieldMissingThrowsForStrictExpression() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowVariableResolver.resolveValue("${user.missing.deeper}", scope(), "node-1"));
    }

    @Test
    public void optionalNestedExpressionReturnsNullWhenMissing() {
        assertNull(WorkflowVariableResolver.resolveValue("${user.missing?}", scope(), "node-1"));
    }

    @Test
    public void nestedFieldSegmentsStopAtNonMapValue() {
        // count 是数字，继续按字段下钻应视为缺失而不是抛转换异常。
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowVariableResolver.resolveValue("${count.value}", scope(), "node-1"));
    }

    @Test
    public void stringifyHandlesNullStringAndObject() {
        assertEquals("", WorkflowVariableResolver.stringify(null));
        assertEquals("text", WorkflowVariableResolver.stringify("text"));
        assertEquals("[1,2]", WorkflowVariableResolver.stringify(Arrays.asList(1, 2)));
        assertEquals("{\"a\":1}",
                WorkflowVariableResolver.stringify(Collections.singletonMap("a", 1)));
    }

    @Test
    public void stringifyFallsBackForUnserializableValue() {
        Object broken = new Object() {
            public String getValue() {
                throw new IllegalStateException("boom");
            }
        };
        // Jackson 序列化失败时退化为 toString，保证提示词替换不中断。
        assertTrue(WorkflowVariableResolver.stringify(broken).contains("@"));
    }

    @Test
    public void trimsSpacesAroundExpression() {
        assertEquals("clara", WorkflowVariableResolver.resolveValue("${  name  }", scope(), "node-1"));
    }
}