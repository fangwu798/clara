package com.clara.model.provider.http;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.kernel.InvocationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * 将服务提供商SSE数据解析为领域对象。
 */
public final class ProviderSseParser {
    private final int maxLineBytes;
    private final int maxEventBytes;
    private final int maxEvents;
    private final long maxTotalBytes;

    public ProviderSseParser(int maxLineBytes, int maxEventBytes, int maxEvents, long maxTotalBytes) {
        if (maxLineBytes <= 0 || maxEventBytes <= 0 || maxEvents <= 0 || maxTotalBytes <= 0L) {
            throw new IllegalArgumentException("SSE limits must be positive");
        }
        if (maxLineBytes > maxEventBytes || maxEventBytes > maxTotalBytes) {
            throw new IllegalArgumentException("SSE limits must be ordered");
        }
        this.maxLineBytes = maxLineBytes;
        this.maxEventBytes = maxEventBytes;
        this.maxEvents = maxEvents;
        this.maxTotalBytes = maxTotalBytes;
    }

    public int parse(InputStream input, InvocationContext context, ProviderSseListener listener) {
        if (input == null || context == null || listener == null) {
            throw new IllegalArgumentException("input, context and listener must not be null");
        }
        State state = new State();
        ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maxLineBytes, 256));
        long total = 0L;
        boolean skipLf = false;
        boolean firstLine = true;
        try {
            int value;
            while ((value = input.read()) >= 0) {
                checkContext(context);
                total++;
                if (total > maxTotalBytes) tooLarge();
                if (skipLf) {
                    skipLf = false;
                    if (value == '\n') continue;
                }
                if (value == '\r' || value == '\n') {
                    String decoded = decode(line.toByteArray());
                    line.reset();
                    if (firstLine && decoded.length() > 0 && decoded.charAt(0) == '\ufeff') {
                        decoded = decoded.substring(1);
                    }
                    firstLine = false;
                    if (!accept(decoded, state, listener)) {
                        return state.events;
                    }
                    if (value == '\r') skipLf = true;
                } else {
                    if (line.size() >= maxLineBytes) tooLarge();
                    line.write(value);
                }
            }
            checkContext(context);
            if (line.size() > 0) {
                String decoded = decode(line.toByteArray());
                if (firstLine && decoded.length() > 0 && decoded.charAt(0) == '\ufeff')
                    decoded = decoded.substring(1);
                if (!accept(decoded, state, listener)) return state.events;
            }
            dispatch(state, listener);
            return state.events;
        } catch (ClaraException exception) {
            throw exception;
        } catch (CharacterCodingException exception) {
            protocol();
            return 0;
        } catch (SocketTimeoutException exception) {
            fail(ErrorCodes.MODEL_REQUEST_TIMEOUT, ErrorCategory.TIMEOUT, true);
            return 0;
        } catch (IOException exception) {
            ioError();
            return 0;
        }
    }

    private boolean accept(String line, State state, ProviderSseListener listener) {
        if (line.isEmpty()) return dispatch(state, listener);
        if (line.charAt(0) == ':') return true;
        int lineBytes = utf8Length(line);
        int separatorBytes = state.hasField ? 1 : 0;
        if (state.eventBytes > maxEventBytes - lineBytes - separatorBytes) tooLarge();
        state.eventBytes += lineBytes + separatorBytes;
        state.hasField = true;
        int separator = line.indexOf(':');
        String field = separator < 0 ? line : line.substring(0, separator);
        String value = separator < 0 ? "" : line.substring(separator + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        if ("data".equals(field)) {
            if (state.hasData) state.data.append('\n');
            state.data.append(value);
            state.hasData = true;
        } else if ("event".equals(field)) {
            state.event = value;
        } else if ("id".equals(field) && value.indexOf('\u0000') < 0) {
            state.id = value;
        } else if ("retry".equals(field)) {
            state.retryMillis = retry(value);
        }
        return true;
    }

    private boolean dispatch(State state, ProviderSseListener listener) {
        if (!state.hasData) {
            state.resetEvent();
            return true;
        }
        if (state.events >= maxEvents) tooLarge();
        state.events++;
        ProviderSseEvent event = new ProviderSseEvent(
                state.event == null || state.event.isEmpty() ? "message" : state.event,
                state.data.toString(), state.id, state.retryMillis);
        state.resetEvent();
        return listener.onEvent(event);
    }

    private static Long retry(String value) {
        if (value.isEmpty()) return null;
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void checkContext(InvocationContext context) {
        context.getCancellationToken().throwIfCancellationRequested(context.getTraceId());
        if (context.remainingMillis(System.currentTimeMillis()) <= 0L)
            fail(ErrorCodes.MODEL_REQUEST_TIMEOUT, ErrorCategory.TIMEOUT, true);
    }

    private static void tooLarge() {
        fail(ErrorCodes.MODEL_RESPONSE_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT, false);
    }

    private static void protocol() {
        fail(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE, false);
    }

    private static void ioError() {
        fail(ErrorCodes.MODEL_TRANSPORT_IO_ERROR, ErrorCategory.EXTERNAL_SERVICE, true);
    }

    private static void fail(String code, ErrorCategory category, boolean retryable) {
        throw new ClaraException(new ClaraError(code, category, code, retryable, null,
                null, null, null, Collections.<String, String>emptyMap()));
    }

    /**
     * 表示。
     */
    private static final class State {
        private final StringBuilder data = new StringBuilder();
        private String event;
        private String id;
        private Long retryMillis;
        private int eventBytes;
        private int events;
        private boolean hasField;
        private boolean hasData;

        private void resetEvent() {
            data.setLength(0);
            event = null;
            id = null;
            retryMillis = null;
            eventBytes = 0;
            hasField = false;
            hasData = false;
        }
    }
}
