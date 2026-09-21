package com.clara.model.provider;

import com.clara.kernel.api.IRequest;
import com.clara.kernel.api.IResponse;
import com.clara.kernel.api.IStreamModel;
import com.clara.kernel.api.execute.IHttpExecute;
import com.clara.kernel.stream.IStreamHandle;
import com.clara.kernel.stream.IStreamObserver;

/**
 * {@link IStreamModel} 的唯一实现：把统一调用转发给传输实现，自身不含任何平台分支。
 *
 * <p>是什么、在哪一层：实现层（{@code com.clara.model.provider}）。本类只做「模型调用入口 → 传输实现」的
 * 一层转发——请求怎么编码、响应怎么解码、流式事件怎么映射，全部由 {@link IHttpExecute} 的默认实现借助
 * 字段注解完成，因此本类不需要知道平台、能力或模型。
 *
 * <p>谁创建谁调用：由宿主应用在装配阶段创建，注入需要发起模型调用的组件；实例只持有一个无状态的传输实现，
 * 线程安全，可全局共享，不需要 {@code close()}。
 *
 * <p>使用方法：
 * <pre>{@code
 * IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());
 * try (ConvContext.Scope ignored = ConvContext.open(context)) {
 *     HttpResponse response = model.chat(request, HttpResponse.class);
 * }
 * }</pre>
 *
 * <p>失败语义：入参不合法、线程上下文未绑定平台配置、平台协议错误或网络失败时，直接透传
 * {@link IHttpExecute} 抛出的异常；流式运行期失败以 {@code ERROR} 事件表达，不抛出。
 */
public class CompatibleStream implements IStreamModel {

    /**
     * 底层传输实现，提供端点校验、超时、注解编解码与流式桥接。
     */
    private final IHttpExecute transport;

    /**
     * 创建统一模型调用入口。
     *
     * @param transport 底层传输实现，不可为 null
     * @throws IllegalArgumentException transport 为 null 时
     */
    public CompatibleStream(IHttpExecute transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.transport = transport;
    }

    @Override
    public <R extends IResponse> R chat(IRequest request, Class<R> responseType) {
        return transport.execute(request, responseType);
    }

    @Override
    public <R extends IResponse> IStreamHandle chat(IRequest request, Class<R> responseType, IStreamObserver observer) {
        return transport.stream(request, responseType, observer);
    }
}
