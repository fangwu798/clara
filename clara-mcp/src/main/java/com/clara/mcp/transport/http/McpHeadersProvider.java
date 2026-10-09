package com.clara.mcp.transport.http;

import java.util.Map;

/**
 * 为 streamable http 传输提供每次请求附带的请求头（如鉴权）。
 *
 * <p>实现约定：返回的映射可为 null（视为无附加头）；传输在写入前校验头名 / 头值合法性并拒绝受限头。
 */
public interface McpHeadersProvider {
    Map<String, String> getHeaders();
}
