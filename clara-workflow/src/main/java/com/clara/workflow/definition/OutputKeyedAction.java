package com.clara.workflow.definition;

import com.clara.core.workflow.IWorkflowAction;
import com.clara.dto.workflow.ExecutionScope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动作输出适配器：把动作返回的 {@code {"output": 值}} 重键为节点声明的输出键。
 *
 * <p>是什么、在哪一层：clara-workflow 配置化加载的内部适配器（包 {@code com.clara.workflow.definition}）。
 * 配置化工作流中，AGENT / TOOL 节点的 {@code outputKeys} 声明了该节点写入作用域的变量名，而动作实现按通用
 * 约定返回 {@code {"output": 值}}；引擎要求「返回值键集合 ⊆ 节点输出键」，故此处做一次重键，让动作实现与
 * 配置解耦（动作不必知道自己被哪个变量接收）。
 *
 * <p>规则：动作已按输出键返回时原样透传；否则取 {@code output} 字段（或唯一字段）作为该输出键的值。
 */
final class OutputKeyedAction implements IWorkflowAction {

    private final IWorkflowAction delegate;
    private final String outputKey;

    OutputKeyedAction(IWorkflowAction delegate, String outputKey) {
        this.delegate = delegate;
        this.outputKey = outputKey;
    }

    @Override
    public Map<String, Object> execute(ExecutionScope scope) {
        Map<String, Object> produced = delegate.execute(scope);
        if (produced != null && produced.containsKey(outputKey)) {
            return produced;
        }
        Object value = null;
        if (produced != null) {
            if (produced.containsKey("output")) {
                value = produced.get("output");
            } else if (produced.size() == 1) {
                value = produced.values().iterator().next();
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put(outputKey, value);
        return output;
    }
}
