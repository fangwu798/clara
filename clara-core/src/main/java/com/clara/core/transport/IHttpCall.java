package com.clara.core.transport;

import com.clara.core.transport.sse.ISseEventListener;
import com.clara.exception.HttpTransportException;

import java.io.Closeable;

/**
 * 一次性的 HTTP 调用句柄，可由其他线程取消。
 */
public interface IHttpCall extends Closeable {

    /**
     * 发送请求并读取完整响应体。
     *
     * @return 响应，状态码可为任意值；非 2xx 时响应体为服务端错误体
     * @throws HttpTransportException 端点拒绝、超限、超时、IO、取消或关闭时
     */
    RawHttpResponse execute();

    /**
     * 发送请求；响应为 2xx 且内容类型为 {@code text/event-stream} 时按 SSE 解析并回调，
     * 否则读取完整响应体返回。
     *
     * @param listener 事件监听器，不可为 null
     * @return 响应；SSE 成功解析时响应体为空
     * @throws HttpTransportException 端点拒绝、超限、超时、IO、协议错误、取消或关闭时
     */
    RawHttpResponse execute(ISseEventListener listener);

    /**
     * 取消本次调用并断开连接；重复调用无副作用。
     */
    void cancel();

    /**
     * 关闭调用并释放底层连接；重复调用无副作用。
     */
    @Override
    void close();

    /**
     * 是否已取消。
     *
     * @return 已取消返回 true
     */
    boolean isCancelled();
}
