package com.clara.kernel.http.execute;

import com.clara.context.CallContext;
import com.clara.context.ConvContext;
import com.clara.core.model.IRequest;
import com.clara.core.transport.HttpHeaders;
import com.clara.core.transport.HttpMediaTypes;
import com.clara.exception.HttpTransportException;
import com.clara.core.transport.IHttpCall;
import com.clara.core.transport.IHttpTransport;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import com.clara.kernel.api.execute.IHttpExecute;
import com.clara.kernel.api.execute.ISseListener;
import com.clara.kernel.http.RawHttpFormatter;
import com.clara.kernel.http.TransportConfig;
import com.clara.transport.http.JdkHttpTransport;
import com.clara.utils.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型侧 HTTP 适配器：把 {@link IRequest} 的线格式请求转交通用 {@link IHttpTransport}。
 *
 * <p>本类不再直接管理连接与 SSE 解析，只负责三件事：从 {@code ConvContext} 取传输配置、把
 * {@link CallContextGuard} 作为通用执行守卫、把通用传输异常映射回模型领域错误码。
 * 同步与流式调用共享同一条底层实现。
 */
public final class Jdk8HttpTransport implements IHttpExecute {

    /**
     * 原始 HTTP 报文的诊断日志。
     */
    private static final Logger logger = LoggerFactory.getLogger(Jdk8HttpTransport.class);

    /**
     * 通用 HTTP 传输实现。
     */
    private final IHttpTransport httpTransport;

    /**
     * 使用默认 JDK 传输实现创建适配器。
     */
    public Jdk8HttpTransport() {
        this(new JdkHttpTransport());
    }

    /**
     * 使用指定通用传输实现创建适配器。
     *
     * @param httpTransport 通用 HTTP 传输，不可为 null
     */
    public Jdk8HttpTransport(IHttpTransport httpTransport) {
        if (httpTransport == null) {
            throw new IllegalArgumentException("httpTransport must not be null");
        }
        this.httpTransport = httpTransport;
    }

    @Override
    public RawHttpResponse execute(IRequest iRequest, ISseListener listener) {
        if (iRequest == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        TransportConfig config = ConvContext.current().getProviderConfiguration().getTransport();
        CallContext call = ConvContext.current().newCall(config.getReadTimeoutMillis());
        RawHttpRequest request = iRequest.toRequest();
        if (logger.isDebugEnabled()) {
            logger.debug("{}", RawHttpFormatter.format(request));
        }

        try (IHttpCall httpCall = httpTransport.newCall(request, config.toHttpTransportPolicy(), new CallContextGuard(call))) {
            RawHttpResponse response = listener == null ? httpCall.execute() : httpCall.execute(listener);
            if (listener != null && response.isSuccessful() && !isEventStream(response)) {
                throw ExceptionUtils.protocolError();
            }
            if ((listener == null || !response.isSuccessful()) && logger.isDebugEnabled()) {
                logger.debug("\n{}\n", RawHttpFormatter.format(response));
            }
            return response;
        } catch (HttpTransportException exception) {
            throw map(exception);
        }
    }

    private static boolean isEventStream(RawHttpResponse response) {
        return HttpMediaTypes.isEventStream(HttpHeaders.firstValue(response.getHeaders(), "Content-Type"));
    }

    private static ClaraException map(HttpTransportException exception) {
        switch (exception.getKind()) {
            case ENDPOINT_DENIED:
                return ExceptionUtils.endpointDenied();
            case REQUEST_TOO_LARGE:
                return ExceptionUtils.requestTooLarge();
            case RESPONSE_TOO_LARGE:
                return ExceptionUtils.responseTooLarge();
            case TIMEOUT:
                return ExceptionUtils.requestTimeout();
            case PROTOCOL_ERROR:
                return ExceptionUtils.protocolError();
            case CANCELLED:
                return ExceptionUtils.fail(ErrorCodes.INVOCATION_CANCELLED, ErrorCategory.CANCELLED, false);
            case CLOSED:
            case IO_ERROR:
            default:
                return ExceptionUtils.transportIoError();
        }
    }

}
