package com.clara.media.object;

import com.clara.media.validation.MediaValidationPolicy;

/**
 * 定义对象媒体存储器的配置。
 */
public final class ObjectMediaStoreConfig {
    private final String namespace;
    private final MediaValidationPolicy validationPolicy;

    public ObjectMediaStoreConfig(String namespace, MediaValidationPolicy validationPolicy) {
        if (namespace == null || !namespace.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")
                || namespace.startsWith("/") || namespace.endsWith("/") || namespace.contains("..")) {
            throw new IllegalArgumentException("invalid namespace");
        }
        if (validationPolicy == null) { throw new IllegalArgumentException("validationPolicy must not be null"); }
        this.namespace = namespace;
        this.validationPolicy = validationPolicy;
    }
    public String getNamespace() { return namespace; }
    public MediaValidationPolicy getValidationPolicy() { return validationPolicy; }
}
