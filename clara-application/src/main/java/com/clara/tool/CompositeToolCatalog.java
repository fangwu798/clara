package com.clara.tool;

import com.clara.core.skill.IToolCatalog;
import com.clara.dto.tool.ToolContext;
import com.clara.dto.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合并工具目录：把本地技能工具与外部（MCP）工具合成一份下发声明，本地同名者优先。
 *
 * <p>是什么、在哪一层：application 层对 core {@link IToolCatalog} 的实现（包 {@code com.clara.tool}），
 * 装配在编排层取「本轮可下发工具」的位置上。它只做合并与去重，不感知工具来源的细节。
 *
 * <p>为什么本地优先：调用侧 {@code CompositeToolInvoker} 的路由顺序就是「技能注册表命中即走本地，
 * 否则查外部工具目录」，若下发声明里同名工具以外部为准，模型会认为自己在调用 MCP 工具、实际却由本地
 * 技能执行，二者 Schema 一旦不同就会出现难以排查的参数错误。故这里与路由保持同一优先级。
 *
 * <p>失败语义：任一子目录为 null 视为无工具；工具名为空白或缺少函数签名的声明被跳过，不返回 null。
 */
public final class CompositeToolCatalog implements IToolCatalog {

    private final IToolCatalog primary;
    private final IToolCatalog secondary;

    /**
     * 创建合并目录。
     *
     * @param primary   优先目录（通常是本地技能工具），可为 null（视为无工具）
     * @param secondary 次选目录（通常是外部 MCP 工具），可为 null（视为无工具）
     */
    public CompositeToolCatalog(IToolCatalog primary, IToolCatalog secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public List<ToolContext> availableTools() {
        Map<String, ToolContext> merged = new LinkedHashMap<>();
        collect(primary, merged);
        collect(secondary, merged);
        return Collections.unmodifiableList(new ArrayList<>(merged.values()));
    }

    /**
     * 收集一个目录的工具声明，同名者不覆盖已收集项（先收集的优先）。
     *
     * @param catalog 工具目录，可为 null
     * @param merged  累积结果，不可为 null
     */
    private static void collect(IToolCatalog catalog, Map<String, ToolContext> merged) {
        if (catalog == null) {
            return;
        }
        List<ToolContext> tools = catalog.availableTools();
        if (tools == null) {
            return;
        }
        for (ToolContext tool : tools) {
            if (tool == null) {
                continue;
            }
            ToolSpecification function = tool.getFunction();
            if (function == null || function.getName() == null || function.getName().trim().isEmpty()) {
                continue;
            }
            if (!merged.containsKey(function.getName())) {
                merged.put(function.getName(), tool);
            }
        }
    }
}