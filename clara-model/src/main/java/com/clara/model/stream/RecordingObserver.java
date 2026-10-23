package com.clara.model.stream;

import com.clara.core.model.IStreamModel;
import com.clara.enums.StreamEventEnum;
import com.clara.core.model.IResponse;
import com.clara.exception.ClaraError;
import com.clara.core.model.IStreamObserver;
import com.clara.dto.model.StreamEvent;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallFunction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 记录型流事件观察者：收集增量、拼装工具调用参数，并把终态响应与错误留存下来供调用方读取。
 *
 * <p>是什么、在哪一层：实现层的可复用观察者（包 {@code com.clara.model.stream}）。流式调用要求调用方提供
 * {@link IStreamObserver}，而「等流结束、把正文拼起来、把工具参数片段按 index 拼成完整调用」是每个调用方都要
 * 重复做一遍的事，本类把它收敛成一处，因此不需要每次调用都临时写一个匿名实现。
 *
 * <p>谁创建谁调用：由调用方在发起流式调用前创建，交给
 * {@link IStreamModel#chat(com.clara.core.model.IRequest, Class, IStreamObserver)}；
 * 一次流对应一个实例，不可复用。不需要 {@code close()}。
 *
 * <p>使用方法：
 * <pre>{@code
 * RecordingObserver<HttpResponse> observer = new RecordingObserver<HttpResponse>(HttpResponse.class);
 * try (ConvContext.Scope ignored = ConvContext.open(context)) {
 *     IStreamHandle handle = model.chat(request, HttpResponse.class, observer);
 *     HttpResponse response = observer.awaitResponse(60, TimeUnit.SECONDS);   // 等终态并取回累积响应
 *     System.out.println(observer.text() + " / " + observer.toolCalls());
 * }
 * }</pre>
 *
 * <p>线程安全：{@link #onEvent} 在流的读取线程上被调用，其余读取方法通常在调用线程上使用。终态事件到达前的
 * 读取没有同步保证；{@link #awaitTerminal} 返回 true 之后（或在 {@link #isTerminated()} 为 true 之后），
 * 各读取方法的结果才是稳定的——锁存器本身建立了 happens-before。
 *
 * <p>失败语义：本类不抛异常，也不中断流。{@code ERROR} 事件会把错误留在 {@link #error()} 上并把流标记为终态，
 * 此时 {@link #response()} 返回 null；{@code onEvent} 内部对事件的解析失败只跳过该片段，不影响整条流。
 */
public final class RecordingObserver<R extends IResponse> implements IStreamObserver {

    /**
     * JSON 映射器，用于解析工具参数片段。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 累积响应的期望类型，用于把终态事件里的响应安全地转成 {@code R}。
     */
    private final Class<R> responseType;
    /**
     * 逐事件输出的目标；为 null 表示不输出日志。
     */
    private final PrintStream log;
    /**
     * 日志前缀，用于区分同一用例里的多次流式调用；不输出日志时可为空串。
     */
    private final String tag;
    /**
     * 终态事件（{@code RESPONSE_COMPLETE} 或 {@code ERROR}）到达时放行。
     */
    private final CountDownLatch terminal = new CountDownLatch(1);
    /**
     * 已累积的正文增量。
     */
    private final StringBuilder text = new StringBuilder();
    /**
     * 已累积的推理增量。
     */
    private final StringBuilder reasoning = new StringBuilder();
    /**
     * 工具调用参数按 index 拼装中的中间结果。
     */
    private final Map<Integer, String[]> toolCalls = new LinkedHashMap<>();

    /**
     * 已收到的事件总数。
     */
    private int eventCount;
    /**
     * 已收到的工具参数片段数。
     */
    private int toolArgumentFragments;
    /**
     * 终态累积响应，仅在 {@code RESPONSE_COMPLETE} 且类型匹配时写入。
     */
    private R response;
    /**
     * 终态错误，仅在 {@code ERROR} 时写入。
     */
    private ClaraError error;

    /**
     * 创建不输出日志的观察者。
     *
     * @param responseType 期望的响应类型，不可为 null
     * @throws IllegalArgumentException responseType 为 null 时
     */
    public RecordingObserver(Class<R> responseType) {
        this(responseType, null, "");
    }

    /**
     * 创建观察者，并按需逐事件输出日志。
     *
     * @param responseType 期望的响应类型，不可为 null
     * @param log          逐事件输出的目标，可为 null（表示不输出）
     * @param tag          日志前缀，可为 null（按空串处理）
     * @throws IllegalArgumentException responseType 为 null 时
     */
    public RecordingObserver(Class<R> responseType, PrintStream log, String tag) {
        if (responseType == null) {
            throw new IllegalArgumentException("responseType must not be null");
        }
        this.responseType = responseType;
        this.log = log;
        this.tag = tag == null ? "" : tag;
    }

    /**
     * 接收一个流事件：记数、按类型累积或记终态。
     *
     * @param event 流事件，非 null
     */
    @Override
    public void onEvent(StreamEvent event) {
        eventCount++;
        switch (event.getType()) {
            case TEXT_DELTA:
                text.append(event.getPayload());
                break;
            case REASONING_DELTA:
                reasoning.append(event.getPayload());
                break;
            case TOOL_ARGUMENT_DELTA:
                toolArgumentFragments++;
                acceptToolArgument(event.getPayload());
                break;
            default:
                break;
        }
        if (event.getType() == StreamEventEnum.RESPONSE_COMPLETE) {
            if (responseType.isInstance(event.getResponse())) {
                response = responseType.cast(event.getResponse());
            }
            terminal.countDown();
        } else if (event.getType() == StreamEventEnum.ERROR) {
            error = event.getError();
            terminal.countDown();
        }
        print(event);
    }

    /**
     * 等待终态事件。
     *
     * @param timeout 等待上限，必须为正
     * @param unit    时间单位，不可为 null
     * @return 在超时前收到终态事件时返回 true
     * @throws InterruptedException 等待被中断时
     */
    public boolean awaitTerminal(long timeout, TimeUnit unit) throws InterruptedException {
        return terminal.await(timeout, unit);
    }

    /**
     * 等待终态并取回累积响应；超时或流以错误收尾时抛出断言式失败。
     *
     * <p>这是最常见的用法：一次调用拿到类型化的完整响应，不需要自己处理锁存器与类型转换。
     *
     * @param timeout 等待上限，必须为正
     * @param unit    时间单位，不可为 null
     * @return 累积完成的响应，非 null
     * @throws InterruptedException 等待被中断时
     * @throws IllegalStateException 等待超时、流以错误收尾，或终态响应类型与期望不符时
     */
    public R awaitResponse(long timeout, TimeUnit unit) throws InterruptedException {
        if (!awaitTerminal(timeout, unit)) {
            throw new IllegalStateException("waiting for the stream to finish timed out after " + timeout + " " + unit);
        }
        if (error != null) {
            throw new IllegalStateException("the stream failed: code=" + error.getCode() + " category=" + error.getCategory() + " provider=" + error.getProvider() + " providerCode=" + error.getProviderCode() + " retryable=" + error.isRetryable());
        }
        if (response == null) {
            throw new IllegalStateException("no response of type " + responseType.getName() + " was carried by the terminal event");
        }
        return response;
    }

    /**
     * 本次流是否已经进入终态（完成或失败）。
     *
     * @return 已进入终态时返回 true
     */
    public boolean isTerminated() {
        return terminal.getCount() == 0L;
    }

    /**
     * 取终态累积响应。
     *
     * @return 累积完成的响应；尚未完成或流以错误收尾时返回 null
     */
    public R response() {
        return response;
    }

    /**
     * 取终态错误。
     *
     * @return 错误信息；正常结束时返回 null
     */
    public ClaraError error() {
        return error;
    }

    /**
     * 取已累积的正文增量。
     *
     * @return 正文文本，非 null；没有任何文本增量时为空串
     */
    public String text() {
        return text.toString();
    }

    /**
     * 取已累积的推理增量。
     *
     * @return 推理文本，非 null；没有任何推理增量时为空串
     */
    public String reasoning() {
        return reasoning.toString();
    }

    /**
     * 取已收到的工具参数片段数，用于区分「没有工具调用」与「有调用但参数为空」。
     *
     * @return 片段数，非负
     */
    public int toolArgumentFragments() {
        return toolArgumentFragments;
    }

    /**
     * 取已收到的流事件总数。
     *
     * @return 事件数，非负
     */
    public int eventCount() {
        return eventCount;
    }

    /**
     * 取拼装完成的工具调用列表。
     *
     * <p>流式下平台把工具参数拆成多个片段下发，本类按 OpenAI 兼容协议的约定拼装：同一个 {@code index} 属于
     * 同一次调用，{@code id} 与 {@code function.name} 只在首个片段出现，{@code function.arguments} 逐片追加。
     *
     * @return 工具调用列表，非 null；没有任何调用时为空列表
     */
    public List<ToolCall> toolCalls() {
        List<ToolCall> result = new ArrayList<>();
        for (Map.Entry<Integer, String[]> entry : toolCalls.entrySet()) {
            String[] call = entry.getValue();
            if (call[1] == null && call[2] == null) {
                continue;
            }
            ToolCallFunction function = new ToolCallFunction();
            function.setName(call[1]);
            function.setArguments(call[2] == null ? "" : call[2]);
            ToolCall toolCall = new ToolCall();
            toolCall.setId(call[0]);
            toolCall.setType("function");
            toolCall.setFunction(function);
            result.add(toolCall);
        }
        return result;
    }

    /**
     * 拼装一段工具参数片段。
     *
     * @param payload 片段 JSON 文本，可为 null（忽略）
     */
    private void acceptToolArgument(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (IOException exception) {
            print("忽略无法解析的工具参数片段: " + payload);
            return;
        }
        if (!root.isArray()) {
            return;
        }
        for (JsonNode element : root) {
            mergeToolCall(element);
        }
    }

    private void mergeToolCall(JsonNode element) {
        int index = element.has("index") ? element.get("index").asInt() : 0;
        String[] call = toolCall(index);
        if (element.hasNonNull("id")) {
            call[0] = element.get("id").asText();
        }
        JsonNode function = element.get("function");
        if (function != null) {
            mergeFunction(call, function);
        }
    }

    private String[] toolCall(int index) {
        String[] call = toolCalls.computeIfAbsent(index, k -> new String[3]);
        return call;
    }

    private static void mergeFunction(String[] call, JsonNode function) {
        if (function.hasNonNull("name")) {
            call[1] = function.get("name").asText();
        }
        if (function.hasNonNull("arguments")) {
            call[2] = (call[2] == null ? "" : call[2]) + function.get("arguments").asText();
        }
    }

    /**
     * 按需输出一个事件。
     *
     * @param event 流事件，非 null
     */
    private void print(StreamEvent event) {
        if (log == null) {
            return;
        }
        switch (event.getType()) {
            case TOOL_ARGUMENT_DELTA:
                print("TOOL_ARGUMENT_DELTA " + event.getPayload());
                break;
            case TOOL_CALL_COMPLETE:
                print("TOOL_CALL_COMPLETE id=" + event.getToolCallId()
                        + " name=" + event.getToolName() + " 参数=" + event.getPayload());
                break;
            case RESPONSE_COMPLETE:
                print("RESPONSE_COMPLETE 结束原因=" + event.getPayload()
                        + " 正文长度=" + text.length() + " 工具调用数=" + toolCalls.size());
                break;
            case ERROR:
                print("ERROR 负载=" + event.getPayload() + " 错误=" + event.getError());
                break;
            default:
                print(event.getType() + " " + event.getPayload());
                break;
        }
    }

    /**
     * 输出一行带前缀的日志。
     *
     * @param message 日志内容，非 null
     */
    private void print(String message) {
        if (log == null) {
            return;
        }
        log.println(tag.isEmpty() ? message : "[" + tag + "] " + message);
    }
}
