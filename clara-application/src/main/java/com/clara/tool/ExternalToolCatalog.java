package com.clara.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部工具路由目录：线程安全的「工具名 → {@link ExternalToolBinding}」登记表。
 *
 * <p>是什么、在哪一层：application 装配层（包 {@code com.clara.tool}），是外部工具路由的唯一登记点。
 * 它只回答「这个名字是不是外部工具、走哪个 MCP 服务端」，不持有 MCP 客户端，也不发起调用。
 *
 * <p>谁创建谁调用：由 {@code ApplicationBootstrap} 创建并持有；宿主 / 能力发现流程在发现外部工具后调用
 * {@link #register(String, ExternalToolBinding)} 登记，工具往返时由 {@link CompositeToolInvoker} 调用
 * {@link #find(String)} 判定外部分支并取回路由信息。
 *
 * <p>生命周期与线程安全：内部用 {@link ConcurrentHashMap} 存储，登记、查找、清空可并发进行；{@link #snapshot()}
 * 返回当次读取的不可变副本，用于观测与审计。
 */
public final class ExternalToolCatalog {

    private final Map<String, ExternalToolBinding> bindings =
            new ConcurrentHashMap<String, ExternalToolBinding>();

    /**
     * 登记一个外部工具的路由绑定，同名重复登记以最后一次为准。
     *
     * @param toolName 工具名，不可为空白
     * @param binding  路由绑定，不可为 null
     * @throws IllegalArgumentException 参数非法时
     */
    public void register(String toolName, ExternalToolBinding binding) {
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (binding == null) {
            throw new IllegalArgumentException("binding must not be null");
        }
        bindings.put(toolName, binding);
    }

    /**
     * 按工具名查找路由绑定。
     *
     * @param toolName 工具名，可为 null
     * @return 命中的路由绑定；未命中（含 toolName 为 null）时返回 null
     */
    public ExternalToolBinding find(String toolName) {
        if (toolName == null) {
            return null;
        }
        return bindings.get(toolName);
    }

    /**
     * 清空全部登记。
     */
    public void clear() {
        bindings.clear();
    }

    /**
     * 返回当次登记的不可变快照，供观测或审计使用。
     *
     * @return 工具名到路由绑定的不可变映射，非 null
     */
    public Map<String, ExternalToolBinding> snapshot() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<String, ExternalToolBinding>(bindings));
    }
}
