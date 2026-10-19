package com.clara.mcp.api;

import com.clara.mcp.transport.McpDecodedMessage;
import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;

/**
 * MCP JSON 编解码：在 JSON-RPC 消息对象与线格式 JSON 文本之间互转，协议编解码集中于此。
 *
 * <p>是什么、在哪一层：传输层的纯函数组件，被 stdio / HTTP 传输共用；只处理 JSON-RPC 2.0 信封，
 * 不解释 MCP 方法语义。
 *
 * <p>实现约定：{@link #encode} 输出单行 JSON（不含换行，stdio 由传输层补行分隔符）；
 * {@link #decode} 把文本解析为响应、通知或服务端请求，非法 JSON / 缺少 {@code jsonrpc=2.0} / 结构不符时
 * 抛 {@code MCP_INVALID_MESSAGE} 领域错误。
 *
 * <p>备注：实现应无状态、线程安全，同一实例可被多个传输并发使用；解码结果中的 {@code result}
 * 统一为 {@code Map} / {@code List} 结构，避免因 MCP 实现多带字段而反序列化失败。
 */
public interface IMcpJsonCodec {
    /**
     * 编码 JSON-RPC 请求（带 id）。
     *
     * @param request 请求消息，不可为 null
     * @return 单行 JSON 文本，非 null
     */
    String encode(JsonRpcRequest request);

    /**
     * 编码 JSON-RPC 通知（不带 id）。
     *
     * @param notification 通知消息，不可为 null
     * @return 单行 JSON 文本，非 null
     */
    String encode(JsonRpcNotification notification);

    /**
     * 解码任意一条 JSON-RPC 消息，并按是否带 id / method 判定为响应、通知或服务端请求。
     *
     * @param json 单行或多行 JSON 文本，不可为空白
     * @return 解码结果，非 null
     */
    McpDecodedMessage decode(String json);
}
