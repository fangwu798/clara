package com.clara.mcp.client.call;

import com.clara.utils.StringUtils;
import com.clara.context.CallContext;
import com.clara.context.ConvContext;
import com.clara.mcp.registry.ImmutableValues;
import lombok.Getter;

import java.util.Map;

/**
 * 封装MCP提示词操作的输入参数：服务端身份（服务端键 + 快照）、提示词名与 Schema 哈希、上下文。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 客户端的调用入参值对象，由宿主应用创建后交给
 * {@code IMcpGateway.getPrompt}；网关按其中的 {@code serverKey} 定位服务端并核对
 * {@code schemaHash} 后取回提示词。
 *
 * <p>使用方法：{@code new McpPromptRequest(serverKey, snapshotId, promptName, schemaHash, arguments,
 * conversation, call)}；参数 map 会被拷贝为只读视图。
 *
 * <p>生命周期与线程安全：构造后不可变，可跨线程共享。
 *
 * <p>失败语义：{@code serverKey}、{@code snapshotId}、{@code promptName}、
 * {@code schemaHash} 为空白，或 {@code conversation} / {@code call} 为 null 时构造抛
 * {@link IllegalArgumentException}。
 */
@Getter
public final class McpPromptRequest {
    private final String serverKey;
    private final String snapshotId;
    private final String promptName;
    private final String schemaHash;
    private final Map<String, Object> arguments;
    private final ConvContext conversation;
    private final CallContext call;

    @SuppressWarnings("java:S107")
    public McpPromptRequest(String serverKey, String snapshotId, String promptName,
            String schemaHash, Map<String, Object> arguments, ConvContext conversation,
            CallContext call) {
        if (StringUtils.isBlank(serverKey) || StringUtils.isBlank(snapshotId) || StringUtils.isBlank(promptName)
                || StringUtils.isBlank(schemaHash)) {
            throw new IllegalArgumentException("prompt request identifiers must not be blank");
        }
        if (conversation == null) { throw new IllegalArgumentException("conversation must not be null"); }
        if (call == null) { throw new IllegalArgumentException("call must not be null"); }
        this.serverKey = serverKey;
        this.snapshotId = snapshotId;
        this.promptName = promptName;
        this.schemaHash = schemaHash;
        this.arguments = ImmutableValues.copyMap(arguments);
        this.conversation = conversation;
        this.call = call;
    }

}
