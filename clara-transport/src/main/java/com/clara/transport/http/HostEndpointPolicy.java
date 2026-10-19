package com.clara.transport.http;

import com.clara.utils.HttpUrisUtils;
import com.clara.core.transport.IEndpointPolicy;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 按主机名做白名单校验，适用于 MCP 这类只声明允许主机的场景。
 */
public final class HostEndpointPolicy implements IEndpointPolicy {

    private final Set<String> allowedHosts;
    private final boolean allowHttp;

    /**
     * 创建主机白名单策略。
     *
     * @param hosts 允许的主机名，非空且不得含空白
     * @param allowHttp 是否允许明文 HTTP
     */
    public HostEndpointPolicy(Collection<String> hosts, boolean allowHttp) {
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalArgumentException("allowedHosts must not be empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String host : hosts) {
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("allowed host must not be blank");
            }
            normalized.add(host.trim().toLowerCase(Locale.ROOT));
        }
        this.allowedHosts = Collections.unmodifiableSet(normalized);
        this.allowHttp = allowHttp;
    }

    public boolean allows(URI endpoint) {
        try {
            HttpUrisUtils.requireAbsolute(endpoint, allowHttp);
            return allowedHosts.contains(endpoint.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
