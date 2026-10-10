package com.clara.kernel.http.execute;

import com.clara.context.CancellationToken;
import com.clara.context.ConvContext;
import com.clara.enums.FinishReasonEnum;
import com.clara.enums.StreamEventEnum;
import com.clara.core.model.IRequest;
import com.clara.core.model.IResponse;
import com.clara.kernel.api.execute.IHttpExecute;
import com.clara.kernel.api.execute.ISseListener;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.kernel.http.execute.sse.SseEvent;
import com.clara.kernel.stream.IStreamHandle;
import com.clara.kernel.stream.IStreamObserver;
import com.clara.dto.model.StreamEvent;
import com.clara.utils.ExceptionUtils;
import com.clara.kernel.message.provider.ProviderResponseDecoder;
import com.clara.kernel.message.provider.StreamAssembler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一流式链路：把传输层的原始 SSE 事件映射为领域流事件，并把增量累加成完整响应对象。
 *
 * <p>是什么、在哪一层：实现层（{@code com.clara.kernel.http.execute}）的流式桥接类。它同时扮演两个角色——
 * 对传输层是 {@link ISseListener}（接收原始 {@link SseEvent}），对调用方是 {@link IStreamHandle}
 * （取消、查询结束、取回累积响应）。平台与能力仍来自线程上下文（{@code ConvContext}），
 * 字段路径来自响应对象上的 {@code @ResponseAdapter} 注解，因此本类不含任何平台分支。
 *
 * <p>谁创建、谁调用：由 {@link IHttpExecute#stream} 创建并立即启动；调用方只持有返回的句柄并注册
 * {@link IStreamObserver}。
 *
 * <p>使用方法：
 * <ol>
 *   <li>调用方实现 {@link IStreamObserver} 接收增量事件；</li>
 *   <li>入口返回本句柄后，调用方轮询 {@link #isDone()} 或等待终态事件；</li>
 *   <li>需要中断时调 {@link #cancel()}——它置位会话取消令牌（传输层逐字节检查）、中断读取线程并立即进入
 *       终态，以 {@code RESPONSE_COMPLETE}（payload 为 {@link FinishReasonEnum#CANCELLED}）收尾；</li>
 *   <li>结束后用 {@link #result()} 取累积响应（未结束、已取消或失败时为 null）。</li>
 * </ol>
 *
 * <p>事件语义：一次流中 {@code RESPONSE_COMPLETE} 与 {@code ERROR} 只会出现其中一个且只出现一次；
 * 增量事件的 sequence 由本类统一编号，从 0 开始单调递增。
 *
 * <p>生命周期与线程安全：读取在单独的守护线程上执行，句柄状态用原子标记保护，可被其他线程安全地
 * 取消与查询；一次流对应一个句柄，不可复用。
 *
 * <p>失败语义：{@code transport}、{@code request}、{@code responseType}、{@code observer} 为 null，
 * 或响应类型缺少无参构造器、线程上下文未绑定平台配置时抛 {@link IllegalArgumentException} /
 * {@link IllegalStateException}；运行期失败（协议错误、超时、读取中断等）不抛出，而是以携带
 * {@link ClaraError} 的 {@code ERROR} 事件表达。
 */
public final class StreamHandle implements IStreamHandle, ISseListener {

    /**
     * 底层传输实现。
     */
    private final IHttpExecute transport;
    /**
     * 待发送的请求。
     */
    private final IRequest request;
    /**
     * 响应累加器，负责按注解把增量解到响应对象上。
     */
    private final StreamAssembler<? extends IResponse> assembler;
    /**
     * 领域事件接收方。
     */
    private final IStreamObserver observer;
    /**
     * 提交本次流时所在线程的会话上下文；为 null 表示提交线程未绑定会话。
     *
     * <p>必须在这里抓住并带到读取线程：ThreadLocal 不跨线程，而读取线程上的请求编码与响应解码都要从
     * {@code ConvContext} 取平台与能力，不重新绑定就会在后台线程上取到 null。
     */
    private final ConvContext context;
    /**
     * 会话取消令牌；为 null 表示线程上下文未提供（此时取消只影响本地状态）。
     */
    private final CancellationToken cancellationToken;
    /**
     * 读取线程。
     */
    private final ExecutorService executor;
    /**
     * 是否已请求取消。
     */
    private final AtomicBoolean cancelled = new AtomicBoolean();
    /**
     * 是否已进入终态（完成、取消或失败）。
     */
    private final AtomicBoolean done = new AtomicBoolean();
    /**
     * 是否以失败收尾。
     */
    private final AtomicBoolean failed = new AtomicBoolean();
    /**
     * 事件序号，由本类统一编号。
     */
    private long sequence;

    /**
     * 创建流式链路；创建后由 {@link #start()} 提交执行。
     *
     * @param transport    底层传输实现，不可为 null
     * @param request      请求对象，不可为 null
     * @param responseType 响应类型，必须有可访问的无参构造器，不可为 null
     * @param observer     领域事件接收方，不可为 null
     * @throws IllegalArgumentException 任一入参为 null，或响应类型缺少无参构造器时
     * @throws IllegalStateException    线程上下文未绑定平台配置或能力种类时
     */
    public StreamHandle(IHttpExecute transport, IRequest request, Class<? extends IResponse> responseType, IStreamObserver observer) {
        if (transport == null || request == null || responseType == null || observer == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        this.transport = transport;
        this.request = request;
        this.assembler = ProviderResponseDecoder.streamAssembler(responseType);
        this.observer = observer;
        ConvContext context = ConvContext.current();
        this.context = context;
        this.cancellationToken = context == null ? null : context.getCancellationToken();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "clara-stream");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 提交后台读取，立即返回本句柄。
     *
     * <p>读取线程会先绑定提交时的会话上下文（若有），因此传输层、请求编码与响应解码在后台线程上都能取到
     * 平台与能力；读取结束或线程退出时自动解绑，不会污染线程池里的下一个任务。
     *
     * @return 本句柄，非 null
     */
    public StreamHandle start() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                if (context == null) {
                    read();
                    return;
                }
                ConvContext.runWith(context, new Runnable() {
                    @Override
                    public void run() {
                        read();
                    }
                });
            }
        });
        return this;
    }

    /**
     * 执行一次读取并把结果落到终态上。
     */
    private void read() {
        try {
            transport.execute(request, StreamHandle.this);
            complete();
        } catch (RuntimeException exception) {
            fail(exception);
        }
    }

    /**
     * 接收传输层解析出的原始 SSE 事件：映射为领域事件、累加增量，并在终态路径命中时收尾。
     *
     * @param event SSE 事件，可为 null（视为空数据）
     * @return 是否继续接收后续事件；已进入终态或本次数据命中结束标记时返回 false
     * @throws ClaraException 数据块不是合法 JSON 或字段类型不符时（由读取线程捕获并以 {@code ERROR} 事件收尾）
     */
    @Override
    public boolean onEvent(SseEvent event) {
        if (done.get()) {
            return false;
        }
        List<StreamEvent> deltas = assembler.accept(event == null ? null : event.getEvent(), event == null ? null : event.getData());
        for (StreamEvent delta : deltas) {
            emit(delta.getType(), delta.getPayload(), null, delta.getToolCallId(), delta.getToolName(), null);
        }
        if (assembler.isTerminated()) {
            ClaraException failure = assembler.failure();
            if (failure != null) {
                fail(failure);
            } else {
                complete();
            }
            return false;
        }
        return true;
    }

    /**
     * 取消本次流：置位会话取消令牌、中断读取线程并立即以完成事件收尾。
     *
     * <p>终态事件为 {@code RESPONSE_COMPLETE}，payload 为 {@link FinishReasonEnum#CANCELLED}。
     *
     * @return 本次调用是否真正触发取消；已结束或此前已取消时返回 false
     */
    @Override
    public boolean cancel() {
        if (done.get() || !cancelled.compareAndSet(false, true)) {
            return false;
        }
        if (cancellationToken != null) {
            cancellationToken.cancel();
        }
        executor.shutdownNow();
        complete();
        return true;
    }

    @Override
    public boolean isDone() {
        return done.get();
    }

    /**
     * 取回累积完成的响应。
     *
     * <p>仅在流正常结束（已 {@code done} 且未取消、未失败）时返回累加结果。
     *
     * @return 累积响应；未结束、已取消或失败时为 null
     */
    @Override
    public IResponse result() {
        return done.get() && !cancelled.get() && !failed.get() ? assembler.result() : null;
    }

    /**
     * 以完成终态收尾（正常结束或取消）。
     */
    private void complete() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        emit(StreamEventEnum.RESPONSE_COMPLETE, cancelled.get() ? FinishReasonEnum.CANCELLED.name() : null,
                assembler.result(), null, null, null);
        executor.shutdown();
    }

    /**
     * 以错误终态收尾。
     *
     * @param exception 运行期异常，非 null
     */
    private void fail(RuntimeException exception) {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        failed.set(true);
        emit(StreamEventEnum.ERROR, null, null, null, null, errorOf(exception));
        executor.shutdown();
    }

    /**
     * 取异常携带的领域错误。
     *
     * @param exception 运行期异常，非 null
     * @return 领域错误；非 {@link ClaraException} 时返回 null
     */
    private static ClaraError errorOf(RuntimeException exception) {
        if (exception instanceof ClaraException) {
            return ((ClaraException) exception).getError();
        }
        return ExceptionUtils.protocolError().getError();
    }

    /**
     * 构造并回调一个领域事件。
     *
     * <p>观察者自身抛出的异常会被吞掉：它不应再次触发回调链，也不应影响本句柄的终态标记。
     *
     * @param type       事件类型，非 null
     * @param payload    事件负载，可为 null
     * @param response   累积响应，仅终态完成事件非 null
     * @param toolCallId 工具调用 ID，可为 null
     * @param toolName   工具名，可为 null
     * @param error      错误信息，仅错误事件非 null
     */
    private synchronized void emit(StreamEventEnum type, String payload, IResponse response, String toolCallId,
            String toolName, ClaraError error) {
        StreamEvent event = new StreamEvent(type, sequence++, payload, toolCallId, toolName, response, error);
        try {
            observer.onEvent(event);
        } catch (RuntimeException ignored) {
        }
    }
}
