package com.clara.workflow.runtime;

import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.enums.CapabilityKindEnum;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * AGENT 节点选项：从动作描述中解析并校验「角色 / 模型别名 / 能力种类 / 图片附件」。
 *
 * <p>是什么、在哪一层：clara-workflow 的值对象（包 {@code com.clara.workflow.runtime}），
 * 由宿主的 AGENT 动作工厂在创建动作时从 {@link WorkflowActionSpec} 解析，供 AGENT 动作按节点
 * 选择模型、能力与多模态输入。
 *
 * <p>属性约定：{@code role}（逻辑角色名）、{@code model}（模型别名）、{@code attachments}（图片引用模板数组）
 * 均可缺省；{@code capability} 缺省为 {@link CapabilityKindEnum#CHAT}，非法取值在解析期即失败。
 * {@code attachments} 的每项为公网图片 URL 或 {@code data:image/...} 引用，可含 {@code ${...}} 变量模板。
 */
public final class AgentNodeOptions {

    private static final String ATTRIBUTE_ROLE = "role";
    private static final String ATTRIBUTE_MODEL = "model";
    private static final String ATTRIBUTE_CAPABILITY = "capability";
    private static final String ATTRIBUTE_ATTACHMENTS = "attachments";

    private final String role;
    private final String modelAlias;
    private final CapabilityKindEnum capability;
    private final List<String> attachments;

    private AgentNodeOptions(String role, String modelAlias, CapabilityKindEnum capability,
            List<String> attachments) {
        this.role = role;
        this.modelAlias = modelAlias;
        this.capability = capability;
        this.attachments = attachments;
    }

    /**
     * 从动作描述解析节点选项。
     *
     * @param spec 动作描述，不可为 null
     * @return 节点选项，非 null
     * @throws IllegalArgumentException spec 为 null、capability 非法或 attachments 类型不符时
     */
    public static AgentNodeOptions fromSpec(WorkflowActionSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        return new AgentNodeOptions(text(spec.getAttribute(ATTRIBUTE_ROLE)),
                text(spec.getAttribute(ATTRIBUTE_MODEL)), capability(spec), attachments(spec));
    }

    /**
     * 逻辑角色名，未指定时 null。
     *
     * @return 角色名
     */
    public String role() {
        return role;
    }

    /**
     * 模型别名，未指定时 null。
     *
     * @return 模型别名
     */
    public String modelAlias() {
        return modelAlias;
    }

    /**
     * 能力种类，非 null（缺省 CHAT）。
     *
     * @return 能力种类
     */
    public CapabilityKindEnum capability() {
        return capability;
    }

    /**
     * 图片附件引用模板列表，非 null（可为空）。
     *
     * @return 附件模板列表（每项可含 {@code ${...}} 变量）
     */
    public List<String> attachments() {
        return attachments;
    }

    private static CapabilityKindEnum capability(WorkflowActionSpec spec) {
        Object raw = spec.getAttribute(ATTRIBUTE_CAPABILITY);
        if (raw == null) {
            return CapabilityKindEnum.CHAT;
        }
        String value = text(raw);
        if (value == null) {
            throw new IllegalArgumentException("AGENT 节点 capability 必须是字符串："
                    + spec.getNodeId());
        }
        for (CapabilityKindEnum candidate : CapabilityKindEnum.values()) {
            if (candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("AGENT 节点 capability 非法：" + value + "（节点 "
                + spec.getNodeId() + "，可选：" + Arrays.toString(CapabilityKindEnum.values()) + "）");
    }

    private static List<String> attachments(WorkflowActionSpec spec) {
        Object raw = spec.getAttribute(ATTRIBUTE_ATTACHMENTS);
        if (raw == null) {
            return Collections.emptyList();
        }
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException("AGENT 节点 attachments 必须是数组：" + spec.getNodeId());
        }
        List<String> attachments = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            String reference = item == null ? null : String.valueOf(item).trim();
            if (reference != null && !reference.isEmpty()) {
                attachments.add(reference);
            }
        }
        return Collections.unmodifiableList(attachments);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value instanceof String ? (String) value : String.valueOf(value);
        text = text.trim();
        return text.isEmpty() ? null : text;
    }
}
