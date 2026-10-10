package com.clara.kernel.api.execute;

import com.clara.exception.ClaraException;
import com.clara.core.model.IRequest;
import com.clara.core.model.IResponse;
import com.clara.dto.model.RawHttpResponse;
import com.clara.kernel.http.execute.StreamHandle;
import com.clara.kernel.stream.IStreamHandle;
import com.clara.kernel.stream.IStreamObserver;
import com.clara.kernel.message.provider.ProviderResponseDecoder;

/**
 * 执行一次服务提供商调用的统一入口，覆盖同步与 SSE 流式两种响应方式。
 *
 * <p>是什么与所属层：本接口是内核的传输 SPI。低层方法只做「发出去、拿回原始响应」；
 * 高层方法在此之上完成<b>注解驱动的解码</b>——平台与能力取自线程上下文（{@code ConvContext}），
 * 字段与平台 JSON 的对应关系由请求 / 响应对象上的注解声明，因此调用方不需要知道平台差异。
 *
 * <p>使用方法（统一调用）：
 * <ol>
 *   <li>构造请求对象（如 {@code HttpRequest}）并设置字段，用 {@code ConvContext.open(...)} 绑定平台与能力；</li>
 *   <li>同步：{@code HttpResponse response = transport.execute(request, HttpResponse.class);}</li>
 *   <li>流式：{@code IStreamHandle handle = transport.stream(request, HttpResponse.class, event -> {...});}
 *       然后轮询 {@code handle.isDone()} 或等终态事件，结束后用 {@code handle.result()} 取完整响应；</li>
 *   <li>需要原始响应（不解码）时用 {@link #execute(IRequest)}；只是想要原始字节、分批拿到 SSE 事件时
 *       用低层的 {@link #execute(IRequest, ISseListener)}。</li>
 * </ol>
 *
 * <p>实现约定：低层方法负责端点白名单、超时、体积上限、取消检查与错误码映射；高层方法不得被实现覆盖成
 * 与低层不一致的语义（默认实现已按注解解码与流式桥接，通常不需要覆盖）。
 *
 * <p>失败语义：参数不合法时抛 {@link IllegalArgumentException}；平台返回非法结构、必需路径缺失时抛
 * {@link ClaraException}（{@code MODEL_PROTOCOL_ERROR}）；流式失败不抛出，
 * 以携带错误信息的 {@code ERROR} 事件表达。
 */
public interface IHttpExecute {

    /**
     * 低层执行：{@code listener} 为 null 时同步读取完整响应体，否则按 SSE 流式解析并回调原始事件。
     *
     * @param request  请求对象，非 null；发送前由实现调用其 {@link IRequest#toRequest()} 编码
     * @param listener SSE 原始事件监听器；为 null 时执行同步调用（因此本参数允许为 null）
     * @return 同步调用返回包含完整响应体的响应；流式调用成功时响应体为空、非 2xx 时携带错误响应体；非 null
     * @throws IllegalArgumentException      请求为 null，或编码阶段入参不合法时
     * @throws UnsupportedOperationException 传入监听器（流式调用）但实现不支持 SSE 时
     * @throws ClaraException 端点被白名单拒绝、请求体超限、超时、传输失败、取消，
     *                                                   或触发协议 / 资源上限错误时
     */
    RawHttpResponse execute(IRequest request, ISseListener listener);

    /**
     * 同步执行调用，等价于 {@code execute(request, (ISseListener) null)}。
     *
     * <p>传 null 时必须写成 {@code (ISseListener) null}：本接口同时有
     * {@link #execute(IRequest, Class) 以响应类型为第二参的重载}，裸 {@code null} 无法定型。
     *
     * @param request HTTP 请求，非 null
     * @return HTTP 响应，包含状态码、响应头与完整响应体；永不为 null
     */
    default RawHttpResponse execute(IRequest request) {
        return execute(request, (ISseListener) null);
    }

    /**
     * 同步调用并按注解解码为响应对象。
     *
     * @param request      请求对象，非 null
     * @param responseType 响应类型，必须有可访问的无参构造器，不可为 null
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 已填充的响应对象，非 null
     * @throws IllegalArgumentException 入参为 null 或响应类型不可实例化时
     * @throws IllegalStateException    注解解码步骤发现线程上下文未绑定平台配置或能力种类时
     * @throws ClaraException 平台返回非法结构、必需路径缺失或类型不符时
     */
    default <R extends IResponse> R execute(IRequest request, Class<R> responseType) {
        if (request == null || responseType == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        return ProviderResponseDecoder.decode(execute(request, (ISseListener) null), responseType);
    }

    /**
     * 流式调用：立即返回句柄，解码后的增量事件通过 {@code observer} 回调，终态事件携带累积响应。
     *
     * @param request      请求对象，非 null；其 {@code stream} 字段应置为 true，否则平台按非流式返回，
     *                     传输层会因响应内容类型不是 {@code text/event-stream} 而按协议错误失败
     * @param responseType 响应类型，必须有可访问的无参构造器，不可为 null
     * @param observer     领域事件接收方，不可为 null
     * @param <R>          响应类型，需实现 {@link IResponse}
     * @return 可用于取消与查询的句柄，非 null
     * @throws IllegalArgumentException 入参为 null 或响应类型不可实例化时
     * @throws IllegalStateException    注解编解码步骤（流式链路构造）发现线程上下文未绑定平台配置或能力种类时
     */
    default <R extends IResponse> IStreamHandle stream(IRequest request, Class<R> responseType, IStreamObserver observer) {
        return new StreamHandle(this, request, responseType, observer).start();
    }
}
