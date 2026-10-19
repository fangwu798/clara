package com.clara.core.transport.sse;

import com.clara.core.transport.IExecutionGuard;
import com.clara.exception.HttpTransportException;

import java.io.InputStream;

/**
 * SSE 字节流解析器。
 */
public interface ISseEventParser {

    /**
     * 解析一个 SSE 输入流。
     *
     * @param input    输入流，非 null；由调用方负责关闭
     * @param guard    取消与截止时间守卫，非 null
     * @param listener 事件监听器，非 null
     * @return 已派发的事件数量，非负
     * @throws HttpTransportException 取消、超时、协议错误或体积超限时
     */
    int parse(InputStream input, IExecutionGuard guard, ISseEventListener listener);
}
