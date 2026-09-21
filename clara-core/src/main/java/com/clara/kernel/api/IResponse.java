package com.clara.kernel.api;

import com.clara.kernel.http.RawHttpResponse;

public interface IResponse {
    /**
     * 模型对应的响应结果
     *
     * @param <T>
     * @return
     */
    <T extends IResponse> Class<T> getCls();

    /**
     * 将当前配置转换成可以请求的请求体中
     * 不同的模型对请求体是有所差异的，根据自己的实现来生成
     *
     * @return
     */
    RawHttpResponse toResponse();
}
