package com.clara.mcp.api;

import com.clara.mcp.registry.McpServerSnapshot;
import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;

/**
 * MCP 能力目录：保存各服务端的能力快照，并按标识解析工具 / 资源 / 提示词。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层的能力目录接口。发现流程 {@code publish} 快照，调用网关在
 * 调用前 {@code resolve*} 解析能力并校验 Schema 哈希，从而把「发现」与「调用」解耦。
 *
 * <p>索引约定：快照按快照标识唯一索引，并按服务端标识维护最新快照。
 *
 * <p>实现约定：{@link #get} 与 {@link #latest} 在缺失时返回 {@code null}；{@code resolve*} 在目录中找不到
 * 能力时抛 {@code MCP_CAPABILITY_NOT_FOUND}，期望 Schema 哈希与目录不一致时抛 {@code MCP_SCHEMA_MISMATCH}。
 *
 * <p>线程安全与备注：实现必须支持并发发布与读取；{@code latest} 默认「发现时间更新者胜出」，
 * 同一 {@code snapshotId} 不可重复发布，快照发布后视为不可变。目录是调用前的真相来源——路由表只负责
 * 找到工具名，是否敢调用必须回到这里校验状态、健康度、能力存在性与 Schema 哈希。
 */
public interface IMcpCapabilityCatalog {
    /**
     * 发布一次发现得到的服务端能力快照。
     *
     * @param snapshot 能力快照，不可为 null
     */
    void publish(McpServerSnapshot snapshot);

    /**
     * 取某服务端的最新快照。
     *
     * @param serverKey 服务端标识，不可为空白
     * @return 最新快照；无快照时返回 {@code null}
     */
    McpServerSnapshot latest(String serverKey);

    /**
     * 按快照标识取回快照。
     *
     * @param serverKey  服务端标识，不可为空白
     * @param snapshotId 快照标识，不可为空白
     * @return 对应快照；不存在时返回 {@code null}
     */
    McpServerSnapshot get(String serverKey, String snapshotId);

    /**
     * 按工具名解析工具描述，并校验 Schema 哈希。
     *
     * @param serverKey          服务端标识，不可为空白
     * @param snapshotId         快照标识，不可为空白
     * @param toolName           工具名，不可为空白
     * @param expectedSchemaHash 期望的入参 Schema 哈希，不可为空白
     * @return 匹配的工具描述，非 null
     */
    McpToolDescriptor resolveTool(String serverKey, String snapshotId,
            String toolName, String expectedSchemaHash);

    /**
     * 按资源 URI 解析资源描述，并校验 Schema 哈希。
     *
     * @param serverKey          服务端标识，不可为空白
     * @param snapshotId         快照标识，不可为空白
     * @param uri                资源 URI，不可为空白
     * @param expectedSchemaHash 期望的 Schema 哈希，不可为空白
     * @return 匹配的资源描述，非 null
     */
    McpResourceDescriptor resolveResource(String serverKey, String snapshotId,
            String uri, String expectedSchemaHash);

    /**
     * 按提示词名解析提示词描述，并校验 Schema 哈希。
     *
     * @param serverKey          服务端标识，不可为空白
     * @param snapshotId         快照标识，不可为空白
     * @param promptName         提示词名，不可为空白
     * @param expectedSchemaHash 期望的 Schema 哈希，不可为空白
     * @return 匹配的提示词描述，非 null
     */
    McpPromptDescriptor resolvePrompt(String serverKey, String snapshotId,
            String promptName, String expectedSchemaHash);
}
