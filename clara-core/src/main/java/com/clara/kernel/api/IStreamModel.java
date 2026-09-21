package com.clara.kernel.api;

import com.clara.kernel.stream.IStreamHandle;
import com.clara.kernel.stream.IStreamObserver;

/**
 * 统一的模型调用入口：不区分具体模型与平台，一次调用 = 一个请求对象 + 一个响应类型。
 *
 * <p>位置：契约层（{@code com.clara.kernel.api}）。调用方只依赖本接口与请求 / 响应对象：
 * 请求字段怎么下发、响应字段从哪里取，全部由字段上的注解描述，平台与能力来自线程上下文
 * （{@code ConvContext}），因此换平台不需要换调用代码，也不需要为每个模型写实现类。
 *
 * <p>使用方法：
 * <ol>
 *   <li>用 {@code ConvContext.open(...)} 绑定平台配置与能力种类；</li>
 *   <li>构造请求对象（如 {@code HttpRequest}）并设置字段；</li>
 *   <li>同步：{@code HttpResponse response = model.chat(request, HttpResponse.class);}</li>
 *   <li>流式：{@code IStreamHandle handle = model.chat(request, HttpResponse.class, event -> {...});}
 *       结束后用 {@code handle.result()} 取累积响应。</li>
 * </ol>
 *
 * <p>失败语义：请求非法、平台协议错误、鉴权失败或网络中断时抛 {@code ClaraException}；流式失败以
 * {@code ERROR} 事件表达，不抛出；调用失败后实现仍可继续使用。
 */
public interface IStreamModel {

    /**
     * 执行一次同步调用并按注解解码响应。
     *
     * @param request      请求对象，不可为 null
     * @param responseType 响应类型，必须有可访问的无参构造器，不可为 null
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 已填充的响应对象，非 null
     * @throws IllegalArgumentException 入参为 null 或响应类型不可实例化时
     * @throws IllegalStateException    线程上下文未绑定平台配置或能力种类时
     * @throws RuntimeException         平台调用失败时（协议错误、鉴权失败、网络异常等）
     */
    <R extends IResponse> R chat(IRequest request, Class<R> responseType);

    /**
     * 提交一次流式调用，立即返回句柄，解码后的增量事件通过 {@code observer} 回调。
     *
     * @param request      请求对象，不可为 null；其 {@code stream} 字段应置为 true
     * @param responseType 响应类型，必须有可访问的无参构造器，不可为 null
     * @param observer     领域事件接收方，不可为 null；实现保证事件按序回调
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 可用于查询完成状态或取消的句柄，非 null
     * @throws IllegalArgumentException 入参为 null 或响应类型不可实例化时
     * @throws IllegalStateException    线程上下文未绑定平台配置或能力种类时
     * @throws RuntimeException         请求在提交阶段即失败时；运行期失败改为回调 {@code ERROR} 事件
     */
    <R extends IResponse> IStreamHandle chat(IRequest request, Class<R> responseType, IStreamObserver observer);
}
