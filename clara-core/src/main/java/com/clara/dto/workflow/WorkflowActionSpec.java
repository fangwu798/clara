package com.clara.dto.workflow;

import com.clara.utils.StringUtils;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流动作描述：配置化动作节点的标识、类型与属性，是加载器与动作工厂之间的公共数据契约。
 *
 * <p>是什么、在哪一层：契约层的配置值对象（包 {@code com.clara.dto.workflow}）。加载器把 YAML 节点解析为
 * 本对象后交给 {@code IWorkflowActionFactory}，由工厂据此创建具体动作。
 *
 * <p>属性在构造时做防御性拷贝并转为不可变；{@link #getAttribute(String)} 返回原始对象（可能是
 * String / List / Map），供工厂按类型读取。
 */
@Data
public final class WorkflowActionSpec {

    private final String nodeId;
    private final String kind;
    private final Map<String, Object> attributes;

    /**
     * 创建动作描述。
     *
     * @param nodeId     节点标识，不可为空白
     * @param kind       动作类型（节点类型名），不可为空白
     * @param attributes 动作属性，可为 null（视为空）
     * @throws IllegalArgumentException nodeId 或 kind 为空白时
     */
    public WorkflowActionSpec(String nodeId, String kind, Map<String, Object> attributes) {
        if (StringUtils.isBlank(nodeId) || StringUtils.isBlank(kind)) {
            throw new IllegalArgumentException("nodeId and kind are required");
        }
        this.nodeId = nodeId;
        this.kind = kind;
        this.attributes = Collections.unmodifiableMap(attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes));
    }

    /**
     * 按名称读取动作属性。
     *
     * @param name 属性名，可为 null
     * @return 属性值（原始对象），不存在时返回 null
     */
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

}
