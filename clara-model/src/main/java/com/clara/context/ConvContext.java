package com.clara.context;

import com.clara.enums.CapabilityKindEnum;
import com.clara.exception.ClaraException;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.TransportConfig;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 会话上下文：标识一次对话会话，并承载该会话内已登记的调用上下文（{@link CallContext}）。
 *
 * <p>是什么、在哪一层：本类属于内核的上下文包（{@code com.clara.context}），是会话级上下文值对象。
 * 它回答「这是哪个会话」，而 {@link CallContext} 回答「这是哪一次调用」：会话标识（{@code convId}）跨请求
 * 稳定，与对话记忆（clara-memory）中的会话标识同源同值；{@code historyCall} 则记录该会话内已经登记过的
 * 调用上下文，{@link #currentCall()} 返回其中登记时间最晚的那一份。
 *
 * <p>谁创建谁调用：由发起调用的业务代码（宿主应用的 Agent / CLI / 请求适配器）按「会话标识 + 平台配置 +
 * 能力种类」创建，随后用 {@link #open(ConvContext)} 等绑定到当前线程；编解码层与模型实现只从线程上下文里读
 * 平台配置与能力种类，传输层（{@code Jdk8HttpTransport}）在真正发出请求时调 {@link #newCall(long)} 登记本次
 * 调用并取回 {@link CallContext}。
 *
 * <p>使用方法：
 * <ol>
 *   <li>不需要预先登记调用上下文时使用四参构造器
 *       {@code new ConvContext(userId, convId, providerConfiguration, capabilityKind)}（{@code historyCall}
 *       为空），此时 {@link #currentCall()} 返回 {@code null}（后台任务、媒体轮询等场景）；</li>
 *   <li>需要让模型层读到跟踪标识与取消令牌时，每轮对话调一次 {@link #newCall(long)}：
 *       它生成新的 {@code traceId}、复用本会话的取消令牌，并把新的 {@link CallContext} 登记进会话调用历史，
 *       会话内多次调用按登记顺序累积，{@link #currentCall()} 返回最近一次登记的那一份；</li>
 *   <li>需要终止本会话（用户取消、客户端断开、CLI 中断）时，任意层调
 *       {@code ConvContext.current().cancel()} 即可置位本会话的取消令牌，正在进行与后续的调用都会在下一个
 *       检查点终止；循环体与关键点用 {@link #throwIfCancelled()} 做快速失败；</li>
 *   <li>没有真实对话的后台调用（如媒体任务轮询）应自造一个稳定的会话标识，例如使用任务标识，以便日志与
 *       观测能把同一类调用归到一组；</li>
 *   <li>需要在下游任意层直接取会话时不靠层层传参：用 {@link #open(ConvContext)}（或 {@link #runWith} /
 *       {@link #callWith}）把处理逻辑包成作用域，退出时自动还原绑定，中途任意位置都用 {@link #current()}
 *       取回——即「一个线程一份会话上下文」；不便于包作用域时，才退化为手工
 *       {@link #bind(ConvContext)} 加 {@code finally} {@link #clear()}。</li>
 * </ol>
 *
 * <p>生命周期与线程安全：除调用历史外字段全部 final，可安全跨线程共享；{@code historyCall} 在构造时拷贝
 * 入参（入参为 null 时视为空 map），之后由 {@link #newCall(long)} 追加、{@link #currentCall()} 读取，
 * 两者在同一把锁上同步，因此同一个会话实例可以被多个线程并发登记调用。
 *
 * <p>线程绑定：绑定存放在 {@link ThreadLocal} 中，<b>只对绑定的那个线程可见</b>，不向子线程继承，也不跨
 * 线程池中的下一个任务沿用——因此必须在 {@code finally} 里 {@link #clear()}，否则复用的工作线程会把上一
 * 个请求的会话带到下一个请求。需要把会话带到线程池或异步任务时，显式把实例传过去并在线程内重新
 * {@link #bind(ConvContext)}。
 *
 * <p>失败语义：{@code convId} 为 null 或空白、{@code cancellationToken} 为 null 时构造抛
 * {@link IllegalArgumentException}；{@link #bind(ConvContext)}
 * 传入 null 时同样抛 {@link IllegalArgumentException}（解绑请用 {@link #clear()}），属于调用方编程
 * 错误，不可重试；{@code userId} 可为 null（没有用户维度时）；{@code historyCall} 可为 null 或空，
 * 此时 {@link #currentCall()} 返回 {@code null} 而不是抛异常；没有绑定过会话的线程，
 * {@link #current()} 返回 {@code null} 而不是抛异常。
 */
@Getter
public final class ConvContext {

    /**
     * 会话所属的用户或服务主体标识；没有用户维度时可以为空（null）。
     */
    private final String userId;
    /**
     * 会话标识；必填、非空白，与对话记忆（clara-memory）中的会话标识同源同值。
     */
    private final String convId;
    /**
     * 该会话内已登记的调用上下文历史，键为 {@code traceId}、按登记顺序排列（{@code LinkedHashMap}）；
     * 构造时拷贝入参（入参为 null 时视为空 map），之后由 {@link #newCall(long)} 追加、{@link #currentCall()}
     * 读取。
     */
    private final Map<String, CallContext> historyCall;
    /**
     * 当前线程绑定的会话上下文；未绑定时为 null。绑定只对绑定它的线程可见，见 {@link #current()}。
     */
    private static final ThreadLocal<ConvContext> CURRENT = new ThreadLocal<>();

    /**
     * 本会话所属平台的配置：传输参数与各能力配置，平台标识与能力端点由它取得；可为 null。
     */
    private final ProviderConfiguration providerConfiguration;

    /**
     * 本会话对应的能力种类（对话 / 图片 / 视频 / 语音等），字段上的适配注解按它决定当前字段是否下发、
     * 从哪条路径取值；可为 null。
     */
    private final CapabilityKindEnum capabilityKind;

    /**
     * 本会话的取消令牌：记录用户 / 上层是否已请求取消，供各层在循环与关键点及时终止。
     */
    private final CancellationToken cancellationToken;


    /**
     * 创建不携带调用上下文历史的会话上下文，等价于 {@code historyCall} 为空 map；取消令牌由本类自建。
     *
     * @param userId                用户或服务主体标识，可为 null
     * @param convId                会话标识，不可为 null 或空白
     * @param providerConfiguration 本会话所属平台的配置，可为 null（编解码阶段取值时需要它）
     * @param capabilityKind        本会话的能力种类，可为 null（编解码阶段匹配适配规则时需要它）
     * @throws IllegalArgumentException convId 为 null 或空白时
     */
    public ConvContext(String userId, String convId, ProviderConfiguration providerConfiguration, CapabilityKindEnum capabilityKind) {
        this(userId, convId, null, providerConfiguration, capabilityKind);
    }

    /**
     * 创建会话上下文，并接受调用方已有的调用上下文历史；取消令牌由本类自建。
     *
     * @param userId                用户或服务主体标识，可为 null
     * @param convId                会话标识，不可为 null 或空白
     * @param historyCall           该会话已登记的调用上下文历史，可为 null 或空（表示暂时没有可用的调用上下
     *                              文）；键建议使用 {@code traceId}，本类会拷贝一份，null 视为空 map
     * @param providerConfiguration 本会话所属平台的配置，可为 null（编解码阶段取值时需要它）
     * @param capabilityKind        本会话的能力种类，可为 null（编解码阶段匹配适配规则时需要它）
     * @throws IllegalArgumentException convId 为 null 或空白时
     */
    public ConvContext(String userId, String convId, Map<String, CallContext> historyCall, ProviderConfiguration providerConfiguration, CapabilityKindEnum capabilityKind) {
        this(userId, convId, historyCall, providerConfiguration, capabilityKind, new CancellationToken());
    }

    /**
     * 创建会话上下文，并显式指定本会话的取消令牌。
     *
     * <p>什么时候需要显式传入：上层已经持有取消信号（例如 Web 层把「客户端断开」映射成同一个令牌）时，
     * 用它构造会话就能让「客户端断开」直接终止本会话内所有进行中的调用；不传则本类自建一个。
     *
     * @param userId                用户或服务主体标识，可为 null
     * @param convId                会话标识，不可为 null 或空白
     * @param historyCall           该会话已登记的调用上下文历史，可为 null 或空；本类会拷贝一份，null 视为空 map
     * @param providerConfiguration 本会话所属平台的配置，可为 null（编解码阶段取值时需要它）
     * @param capabilityKind        本会话的能力种类，可为 null（编解码阶段匹配适配规则时需要它）
     * @param cancellationToken     本会话的取消令牌，不可为 null
     * @throws IllegalArgumentException convId 为 null 或空白，或 cancellationToken 为 null 时
     */
    public ConvContext(String userId, String convId, Map<String, CallContext> historyCall, ProviderConfiguration providerConfiguration, CapabilityKindEnum capabilityKind, CancellationToken cancellationToken) {
        if (isBlank(convId)) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (cancellationToken == null) {
            throw new IllegalArgumentException("cancellationToken must not be null");
        }
        this.userId = userId;
        this.convId = convId;
        this.historyCall = new LinkedHashMap<>();
        if (historyCall != null) {
            this.historyCall.putAll(historyCall);
        }
        this.providerConfiguration = providerConfiguration;
        this.capabilityKind = capabilityKind;
        this.cancellationToken = cancellationToken;
    }

    /**
     * 取回当前线程绑定的会话上下文，供下游任意层免传参取用。
     *
     * @return 当前线程绑定的会话上下文；该线程未绑定过时返回 {@code null}
     */
    public static ConvContext current() {
        return CURRENT.get();
    }

    /**
     * 把会话上下文绑定到当前线程，覆盖该线程上原有的绑定。
     *
     * <p>典型用法：会话入口在开始处理请求时绑定，处理结束时在 {@code finally} 中 {@link #clear()} 解绑。
     * 绑定不会传给子线程，也不会被线程池的下一个任务自动清空，因此漏掉解绑会让后续请求读到旧会话。
     *
     * @param context 要绑定的会话上下文，不可为 null
     * @throws IllegalArgumentException context 为 null 时（解绑请用 {@link #clear()}）
     */
    public static void bind(ConvContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        CURRENT.set(context);
    }

    /**
     * 解绑当前线程的会话上下文。
     *
     * <p>该线程未绑定时调用无副作用；应在 {@code finally} 中调用，避免复用线程读到上一次请求的会话。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 请求取消本会话：本会话内所有进行中的调用都会在下一个检查点终止。
     *
     * <p>幂等，重复调用只有第一次返回 {@code true}。任意层都能通过 {@link #current()} 取到当前会话后调用它
     * ——例如 Web 层的客户端断开事件、CLI 的 Ctrl+C 处理，不需要层层传参。
     *
     * @return 本次调用是否真正改变了状态（首次请求取消返回 {@code true}，此前已请求过返回 {@code false}）
     */
    public boolean cancel() {
        return cancellationToken.cancel();
    }

    /**
     * 本会话是否已被请求取消。
     *
     * @return 已请求取消时返回 {@code true}
     */
    public boolean isCancelled() {
        return cancellationToken.isCancellationRequested();
    }

    /**
     * 已请求取消时立即抛出取消异常，供循环体与关键点做快速失败。
     *
     * <p>归因标识优先用最近一次登记的调用（{@code currentCall().getTraceId()}），尚未登记过调用时退回
     * {@code convId}。
     *
     * @throws ClaraException 已请求取消时（取消类错误码，不可重试）
     */
    public void throwIfCancelled() {
        CallContext current = currentCall();
        cancellationToken.throwIfCancellationRequested(current == null ? convId : current.getTraceId());
    }

    /**
     * 打开一个会话上下文作用域：进入时绑定到当前线程，退出时自动恢复打开前的绑定（此前没有绑定则解绑）。
     *
     * <p>比手写 {@code bind} / {@code finally clear} 更省心的地方是「自动」与「可嵌套」：用
     * try-with-resources 包住处理逻辑即可，异常路径也会还原，且内层作用域关闭后外层绑定仍然有效。
     *
     * <pre>{@code
     * try (ConvContext.Scope ignored = ConvContext.open(conversation)) {
     *     // 这里任意位置 ConvContext.current() 都是 conversation
     * }
     * }</pre>
     *
     * @param context 要绑定的会话上下文，不可为 null
     * @return 作用域句柄，非 null；关闭时还原绑定，重复关闭无副作用
     * @throws IllegalArgumentException context 为 null 时
     */
    public static Scope open(ConvContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Scope scope = new Scope(CURRENT.get());
        CURRENT.set(context);
        return scope;
    }

    /**
     * 在会话上下文作用域内执行一段无返回值的动作，退出后自动还原绑定。
     *
     * @param context 作用域内的会话上下文，不可为 null
     * @param action  要执行的动作，不可为 null
     * @throws IllegalArgumentException context 或 action 为 null 时
     */
    public static void runWith(ConvContext context, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        try (Scope ignored = open(context)) {
            action.run();
        }
    }

    /**
     * 在会话上下文作用域内执行一段有返回值的动作，退出后自动还原绑定。
     *
     * @param context 作用域内的会话上下文，不可为 null
     * @param action  要执行的动作，不可为 null
     * @param <T>     返回值类型
     * @return 动作的返回值，可为 null
     * @throws IllegalArgumentException context 或 action 为 null 时
     */
    public static <T> T callWith(ConvContext context, Supplier<T> action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        try (Scope ignored = open(context)) {
            return action.get();
        }
    }

    /**
     * 会话上下文作用域句柄：关闭时把当前线程的绑定还原为打开前的状态。
     *
     * <p>由 {@link #open(ConvContext)} 或 {@link #runWith(ConvContext, Runnable)} 等创建，配合
     * try-with-resources 使用；本类不持有任何需要释放的资源，关闭动作只是还原 ThreadLocal。
     */
    public static final class Scope implements AutoCloseable {

        /**
         * 打开作用域之前当前线程的绑定；为 null 表示此前没有绑定。
         */
        private final ConvContext previous;
        /**
         * 是否已经关闭，保证重复关闭不产生副作用。
         */
        private boolean closed;

        /**
         * 记录打开作用域之前当前线程的绑定。
         *
         * @param previous 打开前当前线程的绑定，可为 null（表示此前没有绑定）
         */
        private Scope(ConvContext previous) {
            this.previous = previous;
        }

        /**
         * 还原打开作用域之前的绑定。
         *
         * <p>打开前有绑定则恢复该绑定（支持嵌套），没有绑定则解绑；重复调用无副作用。
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }



    /**
     * 为本次对话创建调用上下文并登记进会话调用历史。
     *
     * <p>{@code traceId} 每次重新生成（UUID），取消令牌<b>复用本会话的令牌</b>——因此
     * {@link #cancel()} 一调用，本会话内所有进行中与后续的调用都会在下一个检查点终止；登记后
     * {@link #currentCall()} 返回的就是它。需要某一次调用独立取消时，请自行构造 {@link CallContext}
     * 并传入独立令牌。
     *
     * @param timeoutMillis 本次调用允许的最长耗时，单位毫秒，必须为正
     * @return 新建并已登记的调用上下文，非 null
     * @throws IllegalArgumentException timeoutMillis 非正时
     */
    public CallContext newCall(long timeoutMillis) {
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        CallContext call = new CallContext(UUID.randomUUID().toString(), System.currentTimeMillis() + timeoutMillis, cancellationToken);
        synchronized (historyCall) {
            historyCall.put(call.getTraceId(), call);
        }
        return call;
    }

    /**
     * 按本会话的传输配置推导一次调用的截止时间。
     *
     * <p>当前没有调用方：传输层直接用传输配置里的读取超时调 {@link #newCall(long)}，因此本方法不影响实际
     * 生效的调用超时。
     *
     * @return 建连超时与读取超时之和，单位毫秒
     * @throws IllegalStateException 会话未携带平台配置时
     */
    private long defaultTimeoutMillis() {
        if (providerConfiguration == null) {
            throw new IllegalStateException("provider configuration is required to derive call timeout");
        }
        TransportConfig transport = providerConfiguration.getTransport();
        return (long) transport.getConnectTimeoutMillis() + transport.getReadTimeoutMillis();
    }

    /**
     * 取回本会话中最近一次登记的调用上下文。
     *
     * @return 按登记顺序的最后一项；尚无登记时返回 {@code null}
     */
    public CallContext currentCall() {
        synchronized (historyCall) {
            if (historyCall.isEmpty()) {
                return null;
            }
            CallContext current = null;
            for (CallContext value : historyCall.values()) {
                current = value;
            }
            return current;
        }
    }

    /**
     * 判断字符串是否为 null 或全空白。
     *
     * @param value 待判断的字符串，可为 null
     * @return 为 null、空串或全空白时返回 {@code true}
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
