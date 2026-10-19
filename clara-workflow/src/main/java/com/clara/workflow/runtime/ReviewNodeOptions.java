package com.clara.workflow.runtime;

import com.clara.dto.workflow.WorkflowActionSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * REVIEW 节点选项：从动作描述中解析并校验「角色列表 / 主持人 / 轮次 / 主题 / 提示词」。
 *
 * <p>是什么、在哪一层：clara-workflow 的值对象（包 {@code com.clara.workflow.runtime}），
 * 由宿主的 REVIEW 动作工厂在创建动作时从 {@link WorkflowActionSpec} 解析，供 REVIEW 动作驱动
 * 多角色评审。
 *
 * <p>属性约定：必需 {@code prompt}（讨论提示词标识）、{@code roles}（角色名数组，非空）、
 * {@code moderator}（主持 / 汇总角色）；可选 {@code topic}（讨论主题，可含 {@code ${...}} 变量引用）、
 * {@code rounds}（轮次，缺省 1，须为正整数）。
 */
public final class ReviewNodeOptions {

    private static final String ATTRIBUTE_PROMPT = "prompt";
    private static final String ATTRIBUTE_ROLES = "roles";
    private static final String ATTRIBUTE_MODERATOR = "moderator";
    private static final String ATTRIBUTE_TOPIC = "topic";
    private static final String ATTRIBUTE_ROUNDS = "rounds";

    private static final int DEFAULT_ROUNDS = 1;

    private final String promptId;
    private final List<String> roles;
    private final String moderator;
    private final String topic;
    private final int rounds;

    private ReviewNodeOptions(String promptId, List<String> roles, String moderator, String topic, int rounds) {
        this.promptId = promptId;
        this.roles = roles;
        this.moderator = moderator;
        this.topic = topic;
        this.rounds = rounds;
    }

    /**
     * 从动作描述解析节点选项。
     *
     * @param spec 动作描述，不可为 null
     * @return 节点选项，非 null
     * @throws IllegalArgumentException spec 为 null、必填项缺失或类型非法时
     */
    public static ReviewNodeOptions fromSpec(WorkflowActionSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        String promptId = requiredText(spec, ATTRIBUTE_PROMPT);
        String moderator = requiredText(spec, ATTRIBUTE_MODERATOR);
        List<String> roles = roles(spec);
        String topic = text(spec.getAttribute(ATTRIBUTE_TOPIC));
        int rounds = rounds(spec);
        return new ReviewNodeOptions(promptId, roles, moderator, topic, rounds);
    }

    /**
     * 讨论提示词标识，非空白。
     *
     * @return 提示词标识
     */
    public String promptId() {
        return promptId;
    }

    /**
     * 参与讨论的角色名列表，非 null 且非空。
     *
     * @return 角色名列表
     */
    public List<String> roles() {
        return roles;
    }

    /**
     * 主持 / 汇总角色名，非空白。
     *
     * @return 主持人角色名
     */
    public String moderator() {
        return moderator;
    }

    /**
     * 讨论主题模板，未指定时 null。
     *
     * @return 主题模板
     */
    public String topic() {
        return topic;
    }

    /**
     * 讨论轮次，正整数。
     *
     * @return 轮次
     */
    public int rounds() {
        return rounds;
    }

    private static List<String> roles(WorkflowActionSpec spec) {
        Object raw = spec.getAttribute(ATTRIBUTE_ROLES);
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException("REVIEW 节点 " + ATTRIBUTE_ROLES + " 必须是数组："
                    + spec.getNodeId());
        }
        List<String> roles = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            String role = item == null ? null : String.valueOf(item).trim();
            if (role != null && !role.isEmpty()) {
                roles.add(role);
            }
        }
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("REVIEW 节点 " + ATTRIBUTE_ROLES + " 不能为空："
                    + spec.getNodeId());
        }
        return Collections.unmodifiableList(roles);
    }

    private static int rounds(WorkflowActionSpec spec) {
        Object raw = spec.getAttribute(ATTRIBUTE_ROUNDS);
        if (raw == null) {
            return DEFAULT_ROUNDS;
        }
        int value;
        if (raw instanceof Number) {
            value = ((Number) raw).intValue();
        } else {
            try {
                value = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("REVIEW 节点 " + ATTRIBUTE_ROUNDS + " 必须是整数："
                        + spec.getNodeId());
            }
        }
        if (value <= 0) {
            throw new IllegalArgumentException("REVIEW 节点 " + ATTRIBUTE_ROUNDS + " 必须为正整数："
                    + spec.getNodeId());
        }
        return value;
    }

    private static String requiredText(WorkflowActionSpec spec, String attribute) {
        String value = text(spec.getAttribute(attribute));
        if (value == null) {
            throw new IllegalArgumentException("REVIEW 节点缺少 " + attribute + "：" + spec.getNodeId());
        }
        return value;
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
