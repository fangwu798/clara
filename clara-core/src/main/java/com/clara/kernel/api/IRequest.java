package com.clara.kernel.api;

import com.clara.kernel.http.RawHttpRequest;

public interface IRequest {

    /**
     * 返回实际的请求类型
     *
     * @param <T>
     * @return
     */
    <T extends IRequest> Class<T> getCls();

    /**
     * 将当前配置转换成可以请求的请求体中
     * 不同的模型对请求体是有所差异的，根据自己的实现来生成
     *
     * @return
     */
    RawHttpRequest toRequest();

    /**
     * 获取请求对应的响应方式
     *
     * @return
     */
    Boolean getStream();
}
