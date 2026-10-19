package com.clara.mcp.api;

import java.util.Map;

/**
 * streamable http 传输的请求头 SPI：为每次请求提供额外请求头（典型用途是鉴权）。
 *
 * <p>使用方：{@code StreamableHttpMcpTransport} 在 POST 与 GET 事件流请求前调用；
 * 工厂也会用它把注册项里的静态 headers 包装成 provider。
 *
 * <p>实现约定：返回 {@code null} 视为无附加头；每次请求都会调用，可实现动态令牌刷新。
 * 头名 / 头值不得包含 CR/LF，且不得覆盖 {@code Host} / {@code Content-Length} / {@code Connection}，
 * 否则传输层拒绝发送。
 *
 * <p>备注：provider 抛出的异常会作为请求失败上报；实现应避免阻塞，必要时自行缓存令牌。
 */
public interface IMcpHeadersProvider {
    /**
     * 返回本次请求要附加的请求头。
     *
     * @return 头名到值的映射；返回 {@code null} 表示不附加任何头
     */
    Map<String, String> getHeaders();
}
