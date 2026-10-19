package com.clara.core.transport;

/**
 * 通用 HTTP 传输工厂：每次调用创建一个独立的 {@link IHttpCall}。
 */
public interface IHttpTransport {

    /**
     * 创建一次 HTTP 调用。
     *
     * @param request 已校验的请求，不可为 null
     * @param policy 传输策略，不可为 null
     * @param guard 取消与截止时间守卫，不可为 null
     * @return 一次性调用句柄，非 null
     */
    IHttpCall newCall(RawHttpRequest request, HttpTransportPolicy policy, IExecutionGuard guard);
}
