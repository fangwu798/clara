package com.clara.media;

import com.clara.enums.MediaTypeEnum;
import com.clara.utils.StringUtils;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 媒体资产：描述一次已落盘媒体的标识、类型、存储位置与元信息。
 */
@Data
public final class MediaAsset {
    /** 资产标识，不可为空白。 */
    private final String assetId;
    /** 媒体类型，不可为 null。 */
    private final MediaTypeEnum mediaType;
    /** MIME 类型，不可为空白。 */
    private final String mimeType;
    /** 内容字节数，不可为负。 */
    private final long size;
    /** 内容 SHA-256 摘要，不可为空白。 */
    private final String sha256;
    /** 本地存储位置，不可为空白。 */
    private final String storageUri;
    /** 原始来源地址，可为 null。 */
    private final String sourceUri;
    /** 宽度（像素），可为 null。 */
    private final Integer width;
    /** 高度（像素），可为 null。 */
    private final Integer height;
    /** 时长（毫秒），可为 null。 */
    private final Long durationMs;
    /** 所属租户，不可为空白。 */
    private final String ownerTenantId;
    /** 创建时间（Unix epoch 毫秒）。 */
    private final long createdAt;
    /** 过期时间（Unix epoch 毫秒），可为 null。 */
    private final Long expiresAt;
    /** 只读元数据副本，非 null。 */
    private final Map<String, String> metadata;

    /**
     * 由构建器创建媒体资产并完成校验与防御性复制。
     *
     * @param builder 已填充字段的构建器，不能为 null
     * @throws IllegalArgumentException 任意必填字段不合法时
     */
    private MediaAsset(Builder builder) {
        this.assetId = required(builder.assetId, "assetId");
        if (builder.mediaType == null) {
            throw new IllegalArgumentException("mediaType must not be null");
        }
        this.mediaType = builder.mediaType;
        this.mimeType = required(builder.mimeType, "mimeType");
        if (builder.size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        this.size = builder.size;
        this.sha256 = required(builder.sha256, "sha256");
        this.storageUri = required(builder.storageUri, "storageUri");
        this.sourceUri = builder.sourceUri;
        this.width = builder.width;
        this.height = builder.height;
        this.durationMs = builder.durationMs;
        this.ownerTenantId = required(builder.ownerTenantId, "ownerTenantId");
        this.createdAt = builder.createdAt;
        this.expiresAt = builder.expiresAt;
        this.metadata = builder.metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
    }

    /**
     * 创建媒体资产构建器。
     *
     * @param assetId   资产标识，不可为空白
     * @param mediaType 媒体类型，不可为 null
     * @param mimeType  MIME 类型，不可为空白
     * @param size      内容字节数，不可为负
     * @return 构建器，非 null
     */
    public static Builder builder(String assetId, MediaTypeEnum mediaType, String mimeType, long size) {
        return new Builder(assetId, mediaType, mimeType, size);
    }

    private static String required(String value, String name) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * 媒体资产构建器；核心身份字段在工厂方法中传入，其余字段按需设置。
     */
    public static final class Builder {

        private final String assetId;
        private final MediaTypeEnum mediaType;
        private final String mimeType;
        private final long size;
        private String sha256;
        private String storageUri;
        private String sourceUri;
        private Integer width;
        private Integer height;
        private Long durationMs;
        private String ownerTenantId;
        private long createdAt;
        private Long expiresAt;
        private Map<String, String> metadata;

        private Builder(String assetId, MediaTypeEnum mediaType, String mimeType, long size) {
            this.assetId = assetId;
            this.mediaType = mediaType;
            this.mimeType = mimeType;
            this.size = size;
        }

        public Builder sha256(String sha256) {
            this.sha256 = sha256;
            return this;
        }

        public Builder storageUri(String storageUri) {
            this.storageUri = storageUri;
            return this;
        }

        public Builder sourceUri(String sourceUri) {
            this.sourceUri = sourceUri;
            return this;
        }

        public Builder width(Integer width) {
            this.width = width;
            return this;
        }

        public Builder height(Integer height) {
            this.height = height;
            return this;
        }

        public Builder durationMs(Long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder ownerTenantId(String ownerTenantId) {
            this.ownerTenantId = ownerTenantId;
            return this;
        }

        public Builder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder expiresAt(Long expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public MediaAsset build() {
            return new MediaAsset(this);
        }
    }
}
