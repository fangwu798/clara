package com.clara.model.provider.media;

import com.clara.model.api.ModelProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 提供服务提供商媒体载荷的 JSON 转换工具。
 */
public final class MediaJson {
    private final ModelProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();

    public MediaJson(ModelProvider provider) {
        this.provider = provider;
    }

    public byte[] write(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException failure) {
            throw ProviderMediaErrors.protocol(provider);
        }
    }

    public JsonNode read(byte[] value) {
        try {
            JsonNode node = mapper.readTree(value);
            if (node == null) { throw ProviderMediaErrors.protocol(provider); }
            return node;
        } catch (IOException failure) {
            throw ProviderMediaErrors.protocol(provider);
        }
    }

    public static String requiredText(JsonNode parent, String field, ModelProvider provider) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().trim().isEmpty()) {
            throw ProviderMediaErrors.protocol(provider);
        }
        return value.textValue();
    }
}
