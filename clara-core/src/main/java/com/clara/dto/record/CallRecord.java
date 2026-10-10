package com.clara.dto.record;

import com.clara.enums.ModelProviderEnum;
import com.clara.core.model.IRequest;
import com.clara.core.model.IResponse;

/**
 * 调用记录：一次大模型调用的留痕，是后续「对话总结 / 理解」的原始依据。
 *
 * <p>字段口径（严格四项）：请求内容、响应内容、供应商类型、调用模型类型。
 */
public final class CallRecord {

    private final IRequest request;
    private final IResponse response;
    private final ModelProviderEnum provider;
    private final String modelType;

    /**
     * 创建调用记录。
     *
     * @param request   本次请求对象，不可为 null
     * @param response  本次响应对象，不可为 null
     * @param provider  供应商类型，不可为 null
     * @param modelType 调用模型标识，可为 null
     * @throws IllegalArgumentException request、response 或 provider 为 null 时
     */
    public CallRecord(IRequest request, IResponse response, ModelProviderEnum provider, String modelType) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        this.request = request;
        this.response = response;
        this.provider = provider;
        this.modelType = modelType;
    }

    public IRequest request() {
        return request;
    }

    public IResponse response() {
        return response;
    }

    public ModelProviderEnum provider() {
        return provider;
    }

    public String modelType() {
        return modelType;
    }
}
