package com.clara.model.runtime;

import com.clara.model.api.ModelProvider;
import com.clara.kernel.ErrorCodes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 负责注册并查找模型。
 */
public final class ModelRegistry {
    private final Map<ModelKey, RegisteredModel> models =
            new ConcurrentHashMap<ModelKey, RegisteredModel>();

    public void register(RegisteredModel model) {
        if (model == null) { throw new IllegalArgumentException("model must not be null"); }
        ProviderModelDescriptor descriptor = model.getDescriptor();
        ModelKey key = new ModelKey(descriptor.getProvider(), descriptor.getAlias());
        if (models.putIfAbsent(key, model) != null) {
            throw RuntimeErrors.configuration(ErrorCodes.MODEL_ALREADY_REGISTERED);
        }
    }

    public RegisteredModel get(ModelProvider provider, String alias) {
        if (provider == null) { throw new IllegalArgumentException("provider must not be null"); }
        if (alias == null || alias.trim().isEmpty()) { throw new IllegalArgumentException("alias must not be blank"); }
        RegisteredModel model = models.get(new ModelKey(provider, alias));
        if (model == null) { throw RuntimeErrors.configuration(ErrorCodes.MODEL_NOT_REGISTERED); }
        return model;
    }
}
