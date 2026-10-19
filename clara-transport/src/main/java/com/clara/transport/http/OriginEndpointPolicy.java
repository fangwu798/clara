package com.clara.transport.http;

import com.clara.utils.HttpUrisUtils;
import com.clara.core.transport.IEndpointPolicy;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 按源站三元组 {@code scheme://host:port} 做白名单校验。
 */
public final class OriginEndpointPolicy implements IEndpointPolicy {

    private final Set<String> allowedOrigins;
    private final boolean allowHttp;

    /**
     * 创建源站白名单策略。
     *
     * @param origins 允许的源站，非空且不得含 null
     * @param allowHttp 是否允许明文 HTTP
     */
    public OriginEndpointPolicy(Collection<String> origins, boolean allowHttp) {
        if (origins == null || origins.isEmpty() || origins.contains(null)) {
            throw new IllegalArgumentException("allowedOrigins must not be empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String origin : origins) {
            normalized.add(HttpUrisUtils.normalizeOrigin(origin, allowHttp));
        }
        this.allowedOrigins = Collections.unmodifiableSet(normalized);
        this.allowHttp = allowHttp;
    }

    public boolean allows(URI endpoint) {
        try {
            return allowedOrigins.contains(HttpUrisUtils.origin(endpoint, allowHttp));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
