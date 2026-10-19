package com.clara.transport.http;

import com.clara.exception.HttpTransportException;
import com.clara.core.transport.IExecutionGuard;
import com.clara.core.transport.sse.ISseEventListener;
import com.clara.core.transport.sse.ISseEventParser;
import com.clara.core.transport.sse.SseEvent;
import com.clara.core.transport.sse.SseParserConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 基于字节流的 SSE 解析器：严格 UTF-8、可配置行/事件/总量/事件数上限。
 */
public final class DefaultSseEventParser implements ISseEventParser {

    private final SseParserConfig config;

    /**
     * 创建解析器。
     *
     * @param config 解析配置，不可为 null
     */
    public DefaultSseEventParser(SseParserConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    /**
     * 解析 SSE 字节流并逐条回调事件。
     *
     * @param input 输入流，不能为 null
     * @param guard 执行守卫，不能为 null
     * @param listener 事件监听器，不能为 null
     * @return 已派发的事件数
     */
    @Override
    public int parse(InputStream input, IExecutionGuard guard, ISseEventListener listener) {
        if (input == null || guard == null || listener == null) {
            throw new IllegalArgumentException("input, guard and listener must not be null");
        }
        State state = new State(Math.min(config.getMaxLineBytes(), 256));
        try {
            read(input, guard, listener, state);
            if (!state.stopped) {
                finish(guard, listener, state);
            }
            return state.events;
        } catch (CharacterCodingException exception) {
            throw HttpTransportException.protocolError("invalid utf-8 in sse stream");
        } catch (SocketTimeoutException exception) {
            throw HttpTransportException.timeout();
        } catch (IOException exception) {
            throw HttpTransportException.ioError(exception);
        }
    }

    private void read(InputStream input, IExecutionGuard guard, ISseEventListener listener,
            State state) throws IOException {
        int value;
        while ((value = input.read()) >= 0) {
            guard.check();
            countByte(state);
            if (skipLeadingLf(value, state)) {
                continue;
            }
            if (value == '\r' || value == '\n') {
                onLineBreak((char) value, listener, state);
                if (state.stopped) {
                    return;
                }
            } else {
                append(state, value);
            }
        }
    }

    private void countByte(State state) {
        state.total++;
        if (state.total > config.getMaxTotalBytes()) {
            throw HttpTransportException.responseTooLarge();
        }
    }

    private static boolean skipLeadingLf(int value, State state) {
        if (!state.skipLf) {
            return false;
        }
        state.skipLf = false;
        return value == '\n';
    }

    private void append(State state, int value) {
        if (state.line.size() >= config.getMaxLineBytes()) {
            throw HttpTransportException.responseTooLarge();
        }
        state.line.write(value);
    }

    private void onLineBreak(char value, ISseEventListener listener, State state)
            throws CharacterCodingException {
        String decoded = takeLine(state);
        if (state.firstLine && !decoded.isEmpty() && decoded.charAt(0) == '\ufeff') {
            decoded = decoded.substring(1);
        }
        state.firstLine = false;
        if (shouldStop(decoded, listener, state)) {
            state.stopped = true;
            return;
        }
        if (value == '\r') {
            state.skipLf = true;
        }
    }

    private void finish(IExecutionGuard guard, ISseEventListener listener, State state)
            throws CharacterCodingException {
        guard.check();
        if (!config.isDispatchFinalEventOnEof()) {
            return;
        }
        if (state.line.size() > 0 && shouldStop(takeLine(state), listener, state)) {
            return;
        }
        if (state.hasData) {
            dispatch(state, listener);
        }
    }

    private static String takeLine(State state) throws CharacterCodingException {
        String decoded = decode(state.line.toByteArray());
        state.line.reset();
        return decoded;
    }

    private boolean shouldStop(String line, ISseEventListener listener, State state) {
        if (line.isEmpty()) {
            return !dispatch(state, listener);
        }
        if (line.charAt(0) == ':') {
            return false;
        }
        int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
        int separatorBytes = state.hasField ? 1 : 0;
        if (state.eventBytes > config.getMaxEventBytes() - lineBytes - separatorBytes) {
            throw HttpTransportException.responseTooLarge();
        }
        state.eventBytes += lineBytes + separatorBytes;
        state.hasField = true;

        int separator = line.indexOf(':');
        String field = separator < 0 ? line : line.substring(0, separator);
        String value = separator < 0 ? "" : line.substring(separator + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        applyField(field, value, state);
        return false;
    }

    private static void applyField(String field, String value, State state) {
        if ("data".equals(field)) {
            if (state.hasData) {
                state.data.append('\n');
            }
            state.data.append(value);
            state.hasData = true;
        } else if ("event".equals(field)) {
            state.event = value;
        } else if ("id".equals(field) && value.indexOf('\u0000') < 0) {
            state.id = value;
        } else if ("retry".equals(field)) {
            state.retryMillis = parseDigitsOrNull(value);
        }
    }

    private boolean dispatch(State state, ISseEventListener listener) {
        if (!state.hasData) {
            state.resetEvent();
            return true;
        }
        if (state.events >= config.getMaxEvents()) {
            throw HttpTransportException.responseTooLarge();
        }
        state.events++;
        SseEvent event = new SseEvent(eventType(state), state.data.toString(), state.id,
                state.retryMillis);
        state.resetEvent();
        return listener.onEvent(event);
    }

    private static String eventType(State state) {
        return state.event == null || state.event.isEmpty()
                ? SseParserConfig.DEFAULT_EVENT_TYPE : state.event;
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static Long parseDigitsOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 单次解析的可变状态。
     */
    private static final class State {
        private final ByteArrayOutputStream line;
        private final StringBuilder data = new StringBuilder();
        private String event;
        private String id;
        private Long retryMillis;
        private long total;
        private int eventBytes;
        private int events;
        private boolean hasField;
        private boolean hasData;
        private boolean skipLf;
        private boolean firstLine = true;
        private boolean stopped;

        private State(int lineCapacity) {
            this.line = new ByteArrayOutputStream(lineCapacity);
        }

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
