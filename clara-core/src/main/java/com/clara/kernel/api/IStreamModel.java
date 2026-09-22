package com.clara.kernel.api;

import com.clara.kernel.stream.IStreamHandle;
import com.clara.kernel.stream.IStreamObserver;

/**
 * 统一的模型调用入口：不区分具体模型与平台，一次调用 = 一个请求对象 + 一个响应类型。
 *
 * <p>是什么、在哪一层：契约层（{@code com.clara.kernel.api}），全平台唯一的模型调用抽象。调用方只依赖本
 * 接口与请求 / 响应对象：请求字段怎么下发、响应字段从哪里取，全部由字段上的
 * {@code @ProviderAdapter} / {@code @ResponseAdapter} 注解描述，平台与能力来自线程上下文
 * （{@link com.clara.context.ConvContext}），因此换平台不需要换调用代码，也不需要为每个模型写实现类。
 *
 * <p>谁创建、谁调用：由宿主应用在装配阶段创建唯一实现 {@code CompatibleStream}
 * （{@code com.clara.model.provider}，它只把调用转发给
 * {@link com.clara.kernel.api.execute.IHttpExecute}，自身不含平台分支），再注入需要发起模型调用的组件；
 * 实现不可变、线程安全，可全局共享。发起调用前必须先用 {@code ConvContext.open(...)} 绑定平台配置与能力
 * 种类，否则调用会在取平台配置时直接失败。
 *
 * <p>使用方法：
 * <pre>{@code
 * IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());
 * try (ConvContext.Scope ignored = ConvContext.open(context)) {
 *     HttpResponse response = model.chat(request, HttpResponse.class);
 * }
 * }</pre>
 * 流式调用传入观察者并持有返回的句柄：
 * <pre>{@code
 * IStreamHandle handle = model.chat(request, HttpResponse.class, event -> { });
 * HttpResponse response = (HttpResponse) handle.result();
 * }</pre>
 *
 * <p>两种响应方式：同步方法在当前线程串行完成「编码请求 → 发送 → 按注解解码」并直接返回响应对象；流式
 * 方法提交后立即返回 {@link IStreamHandle}，读取与解码在后台守护线程上进行，增量事件按序回调
 * {@link IStreamObserver}，结束后从句柄取累积响应。
 *
 * <p>线程语义：同步方法阻塞当前线程；流式方法的回调发生在读取线程上，观察者不宜做耗时操作，而句柄的
 * {@link IStreamHandle#cancel()} 与 {@link IStreamHandle#isDone()} 可以从其他线程调用。
 *
 * <p>失败语义：入参为 null、响应类型不可实例化时抛 {@link IllegalArgumentException}；线程上下文未绑定
 * 平台配置或能力种类时抛 {@link IllegalStateException}；平台协议错误、必需路径缺失、端点被拒、超时或
 * 网络中断时抛 {@link com.clara.kernel.exception.ClaraException}（携带 {@code ClaraError}，其中超时与
 * IO 类错误带可重试标记）。非 2xx 响应不会自动抛错，状态码与错误体保留在
 * {@link com.clara.kernel.http.HttpResponse#getRaw()} 中由调用方判断。流式调用的运行期失败不抛出，而是以
 * {@link com.clara.enums.StreamEventEnum#ERROR} 事件结束本次流。调用失败后实现仍可继续使用。
 */
public interface IStreamModel {

    /**
     * 执行一次同步调用：编码请求、发送、按注解把响应解码成 {@code responseType} 的实例后返回。
     *
     * <p>整条链路在当前线程串行完成，返回时响应对象已填充完毕。平台对非 2xx 也按正常响应返回，状态码与
     * 错误体保留在 {@link com.clara.kernel.http.HttpResponse#getRaw()} 里由调用方判断。
     *
     * @param request      请求对象（{@link IRequest} 派生类，如 {@code HttpRequest}），不可为 null；其
     *                     {@code stream} 字段应保持 false（或不设置），否则平台按流式返回，解码会失败
     * @param responseType 响应类型，必须有可访问的无参构造器，不可为 null
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 已填充的响应对象，非 null
     * @throws IllegalArgumentException 入参为 null，或响应类型缺少无参构造器时
     * @throws IllegalStateException    线程上下文未绑定平台配置或能力种类时
     * @throws com.clara.kernel.exception.ClaraException 平台协议错误、必需路径缺失、端点被拒、超时或网络中断时
     */
    <R extends IResponse> R chat(IRequest request, Class<R> responseType);

    /**
     * 提交一次流式调用：立即返回句柄，读取与解码在后台守护线程进行，增量事件按序回调 {@code observer}。
     *
     * <p>提交时即构造流式累加器与读取线程，并把当时的线程上下文带到读取线程，因此后台线程同样能取到平台
     * 配置与能力种类。终态事件——{@link com.clara.enums.StreamEventEnum#RESPONSE_COMPLETE} 或
     * {@link com.clara.enums.StreamEventEnum#ERROR}——只会出现一次，此后
     * {@link IStreamHandle#isDone()} 为 true，累积响应由 {@link IStreamHandle#result()} 取回（未结束、
     * 已取消或失败时为 null）。
     *
     * @param request      请求对象，不可为 null；其 {@code stream} 字段应置为 {@code true}，否则平台按非
     *                     流式一次性返回
     * @param responseType 响应类型，必须有可访问的无参构造器，不可为 null
     * @param observer     领域事件接收方，不可为 null；回调在读取线程上按序发生，其抛出的异常会被吞掉，
     *                     既不会中断本次流，也不影响句柄的终态
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 可用于取消、查询完成状态与取回累积响应的句柄，非 null
     * @throws IllegalArgumentException 入参为 null，或响应类型缺少无参构造器时
     * @throws IllegalStateException    线程上下文未绑定平台配置或能力种类时
     */
    <R extends IResponse> IStreamHandle chat(IRequest request, Class<R> responseType, IStreamObserver observer);
}
