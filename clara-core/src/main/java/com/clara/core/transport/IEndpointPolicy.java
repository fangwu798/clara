package com.clara.core.transport;

import java.net.URI;

/**
 * 端点准入策略：决定一次 HTTP 请求的目标地址是否被允许。
 */
public interface IEndpointPolicy {

    /**
     * 判断端点是否允许访问。
     *
     * @param endpoint 目标端点，可为 null
     * @return 允许时返回 true；endpoint 为 null 时返回 false
     */
    boolean allows(URI endpoint);
}
