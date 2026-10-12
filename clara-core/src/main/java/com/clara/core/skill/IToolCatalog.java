package com.clara.core.skill;

import com.clara.dto.tool.ToolContext;

import java.util.List;

/**
 * 可用工具目录：提供当前可下发给模型的全部工具声明。
 *
 * <p>是什么、在哪一层：core 定义的契约接口（包 {@code com.clara.core.skill}），由 clara-skill 基于技能注册表
 * 实现。编排层据此把「已注册的全部工具」作为请求 {@code tools} 下发，交由模型自主选择调用，避免仅靠语义匹配
 * 的召回决定工具是否可见（语义匹配只用于提示词注入与知识召回）。
 *
 * <p>失败语义：无工具时返回空列表，不返回 null。
 */
public interface IToolCatalog {

    /**
     * 读取当前全部可用工具声明。
     *
     * @return 工具声明列表，非 null；无工具时为空列表
     */
    List<ToolContext> availableTools();
}
