package com.clara.workflow.runtime;

import com.clara.dto.workflow.ExecutionScope;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/**
 * {@link WorkflowVariableResolver} 的可选变量语义测试。
 *
 * <p>视频生成的可选参考图等输入可能不提供：带 {@code ?} 的表达式应解析为 null / 空串，
 * 不带 {@code ?} 的表达式仍应显式失败，避免把拼错的变量静默当成空值。
 */
public class WorkflowVariableResolverTest {

    private final ExecutionScope scope = new ExecutionScope(Collections.<String, Object>emptyMap());

    @Test
    public void optionalWholeExpressionReturnsNullWhenMissing() {
        assertNull(WorkflowVariableResolver.resolveValue("${reference?}", scope, "generate"));
    }

    @Test
    public void optionalExpressionIsReplacedWithEmptyText() {
        assertEquals("附件：", WorkflowVariableResolver.resolveValue("附件：${reference?}", scope, "generate"));
    }

    @Test
    public void strictExpressionStillFailsWhenMissing() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowVariableResolver.resolveValue("${reference}", scope, "generate"));
    }
}
