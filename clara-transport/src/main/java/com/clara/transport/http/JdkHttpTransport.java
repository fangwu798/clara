package com.clara.transport.http;

import com.clara.core.transport.HttpTransportPolicy;
import com.clara.core.transport.IExecutionGuard;
import com.clara.core.transport.IHttpCall;
import com.clara.core.transport.IHttpTransport;
import com.clara.core.transport.RawHttpRequest;

/**
 * 基于 JDK 8 {@link java.net.HttpURLConnection} 的通用 HTTP 传输实现。
 *
 * <p>本类只负责创建一次性的 {@link HttpCall}；连接、超时、体积上限、SSE 解析与资源释放
 * 都由 {@link HttpCall} 承担。
 */
public final class JdkHttpTransport implements IHttpTransport {

    @Override
    public IHttpCall newCall(RawHttpRequest request, HttpTransportPolicy policy, IExecutionGuard guard) {
        if (request == null || policy == null || guard == null) {
            throw new IllegalArgumentException("request, policy and guard must not be null");
        }
        return new HttpCall(request, policy, guard);
    }
}
