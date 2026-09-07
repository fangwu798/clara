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
import java.util.Locale;
import java.util.Map;

/**
 * 传输JDK 8服务提供商SSE消息。
 */
public final class Jdk8ProviderSseTransport implements ProviderSseTransport {
    private final Jdk8ProviderHttpTransportConfig config;
    private final ProviderSseParser parser;

    public Jdk8ProviderSseTransport(Jdk8ProviderHttpTransportConfig config,
            int maxLineBytes, int maxEventBytes, int maxEvents) {
        if (config == null) { throw new IllegalArgumentException("config must not be null"); }
        if (maxEventBytes > config.getMaxResponseBytes()) {
            throw new IllegalArgumentException("event limit exceeds response limit");
        }
        this.config = config;
        this.parser = new ProviderSseParser(maxLineBytes, maxEventBytes, maxEvents,
                config.getMaxResponseBytes());
    }

    public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context,
            ProviderSseListener listener) {
        if (request == null || context == null || listener == null) {
            throw new IllegalArgumentException("request, context and listener must not be null");
        }
        checkContext(context);
        if (!config.allows(request.getEndpoint())) endpointDenied();
        byte[] body = request.getBody();
        if (body.length > config.getMaxRequestBytes()) requestTooLarge();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) request.getEndpoint().toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(effectiveTimeout(context, config.getConnectTimeoutMillis()));
            connection.setReadTimeout(effectiveTimeout(context, config.getReadTimeoutMillis()));
            connection.setRequestMethod(request.getMethod());
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : request.getHeaders().entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            if (body.length > 0) write(connection, body);
            int status = connection.getResponseCode();
            checkContext(context);
            Map<String, List<String>> headers = headers(connection);
            if (status < 200 || status >= 300) {
                InputStream error = connection.getErrorStream();
                byte[] errorBody = error == null ? new byte[0]
                        : readBounded(error, config.getMaxResponseBytes(), context);
                return new ProviderHttpResponse(status, headers, errorBody);
            }
            if (!isEventStream(connection.getContentType())) protocolError();
            InputStream input = connection.getInputStream();
            try { parser.parse(input, context, listener); }
            finally { try { input.close(); } catch (IOException ignored) { } }
            return new ProviderHttpResponse(status, headers, new byte[0]);
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

    private static void write(HttpURLConnection connection, byte[] body) throws IOException {
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        OutputStream output = connection.getOutputStream();
        try { output.write(body); output.flush(); }
        finally { output.close(); }
    }

    private static byte[] readBounded(InputStream input, int maximum, InvocationContext context)
            throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                checkContext(context);
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

    private static boolean isEventStream(String contentType) {
        if (contentType == null) return false;
        int separator = contentType.indexOf(';');
        String mediaType = separator < 0 ? contentType : contentType.substring(0, separator);
        return "text/event-stream".equals(mediaType.trim().toLowerCase(Locale.ROOT));
    }

    private static int effectiveTimeout(InvocationContext context, int configured) {
        long remaining = context.remainingMillis(System.currentTimeMillis());
        if (remaining <= 0L) timeout();
        return (int) Math.min((long) Integer.MAX_VALUE,
                Math.max(1L, Math.min((long) configured, remaining)));
    }

    private static void checkContext(InvocationContext context) {
        context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
        if (context.remainingMillis(System.currentTimeMillis()) <= 0L) timeout();
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
    private static void protocolError() {
        fail(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE, false);
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
