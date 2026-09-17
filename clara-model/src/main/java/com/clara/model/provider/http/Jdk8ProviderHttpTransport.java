package com.clara.model.provider.http;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 传输JDK 8服务提供商HTTP消息。
 */
public final class Jdk8ProviderHttpTransport implements ProviderHttpTransport  {
    private final ProviderHttpTransportConfig config;

    public Jdk8ProviderHttpTransport(ProviderHttpTransportConfig config) {
        if (config == null) { throw new IllegalArgumentException("config must not be null"); }
        this.config = config;
    }

    public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context) {
        if (request == null || context == null) {
            throw new IllegalArgumentException("request and context must not be null");
        }
        context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
        if (!config.allows(request.getEndpoint())) endpointDenied();
        byte[] body = request.getBody();
        if (body.length > config.getMaxRequestBytes()) requestTooLarge();
        int connectTimeout = effectiveTimeout(context, config.getConnectTimeoutMillis());
        int readTimeout = effectiveTimeout(context, config.getReadTimeoutMillis());
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) request.getEndpoint().toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestMethod(request.getMethod());
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : request.getHeaders().entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            if (body.length > 0) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                OutputStream output = connection.getOutputStream();
                try { output.write(body); output.flush(); } finally { output.close(); }
            }
            int status = connection.getResponseCode();
            context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] responseBody = input == null ? new byte[0]
                    : read(input, config.getMaxResponseBytes(), context);
            return new ProviderHttpResponse(status, headers(connection), responseBody);
        } catch (SocketTimeoutException exception) {
            timeout();
            return null;
        } catch (ClaraException exception) {
            throw exception;
        } catch (IOException exception) {
            transportError();
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static int effectiveTimeout(InvocationContext context, int configured) {
        long remaining = context.remainingMillis(System.currentTimeMillis());
        if (remaining <= 0L) timeout();
        long bounded = Math.min((long) configured, remaining);
        return (int) Math.min((long) Integer.MAX_VALUE, Math.max(1L, bounded));
    }

    private static byte[] read(InputStream input, int maximum, InvocationContext context)
            throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) >= 0) {
                context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
                if (context.remainingMillis(System.currentTimeMillis()) <= 0L) timeout();
                if (count == 0) continue;
                if (total > maximum - count) responseTooLarge();
                output.write(buffer, 0, count);
                total += count;
            }
            return output.toByteArray();
        } finally { try { input.close(); } catch (IOException ignored) { } }
    }

    private static Map<String, List<String>> headers(HttpURLConnection connection) {
        Map<String, List<String>> value = connection.getHeaderFields();
        return value == null ? Collections.<String, List<String>>emptyMap() : value;
    }

    private static void endpointDenied() {
        fail(ErrorCodes.MODEL_ENDPOINT_DENIED, ErrorCategory.AUTHORIZATION, false);
    }
    private static void requestTooLarge() {
        fail(ErrorCodes.MODEL_REQUEST_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT, false);
    }
    private static void responseTooLarge() {
        fail(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT, false);
    }
    private static void timeout() {
        fail(ErrorCodes.MODEL_REQUEST_TIMEOUT, ErrorCategory.TIMEOUT, true);
    }
    private static void transportError() {
        fail(ErrorCodes.MODEL_TRANSPORT_IO_ERROR, ErrorCategory.EXTERNAL_SERVICE, true);
    }
    private static void fail(String code, ErrorCategory category, boolean retryable) {
        throw new ClaraException(new ClaraError(code, category, code, retryable, null,
                null, null, null, Collections.<String, String>emptyMap()));
    }
}
