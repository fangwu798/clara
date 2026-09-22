package com.clara.kernel.http.execute.sse;

import com.clara.context.CallContext;
import com.clara.kernel.api.execute.ISseListener;
import com.clara.kernel.exception.ClaraException;
import com.clara.utils.ExceptionUtils;
import com.clara.utils.NumberUtils;
import com.clara.utils.StringUtils;
import lombok.Data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.CharacterCodingException;

/**
 * SSE 解析器：将 SSE 字节流解析为 {@link SseEvent} 并逐个回调监听器。
 *
 * <p>是什么与所属层：本类属于 {@code clara-core} 的 HTTP 层（{@code com.clara.kernel.http.execute.sse}），
 * 是实现层工具，不是模型 API 契约；它按 SSE 规范逐行解析服务提供商的流式响应体，处理
 * {@code data}/{@code event}/{@code id}/{@code retry} 字段、以冒号开头的注释行、CRLF 换行与首个 BOM，
 * 并在解析过程中执行取消、超时与各项体积/数量上限检查，使传输器只负责建立连接与收发字节，无需关心事件
 * 语法细节。
 *
 * <p>谁创建谁调用：由传输实现（{@code Jdk8HttpTransport}）在构造时创建并持有；由传输实现在每次流式调用的
 * 读取线程中调用 {@link #parse}，业务适配器不直接使用本类。
 *
 * <p>使用方法：装配阶段构造实例——多数场景直接用无参构造器 {@link #SseParser()}（四项上限取类内默认值），
 * 需要与传输层响应体上限对齐时用四参构造器显式指定 → 流式调用时把响应体输入流传给
 * {@link #parse} → 事件逐个回调 {@link ISseListener} → 方法返回后由调用方负责关闭输入流。本类没有 close
 * 或释放动作，它不接管输入流的所有权。
 *
 * <p>解析规则（遵循 SSE 规范）：
 * <ol>
 *   <li>行结束符为 {@code \r\n}、{@code \n} 或 {@code \r}；{@code \r\n} 中的 {@code \n} 会被跳过，
 *       不会产生空行。</li>
 *   <li>每行以第一个冒号拆分为字段名与字段值，冒号后的单个前导空格会去掉；无冒号时整行视为字段名、
 *       值为空；以冒号开头的行是注释行（常见于心跳），直接忽略。</li>
 *   <li>空行表示一个事件块的结束：若该块累积过 {@code data} 就派发事件，否则按规范静默丢弃。</li>
 *   <li>到达流末尾时，未以空行收尾的事件块与行尾缺少换行符的残行一律丢弃，不派发也不按行解析——
 *       宁可丢弃可能被截断的尾部，也不把半截事件当成有效事件。</li>
 *   <li>字段语义：多行 {@code data} 用换行符拼接；{@code event} 缺失或为空时使用默认事件名
 *       {@code message}；含 NUL 字符的 {@code id} 按规范忽略；{@code retry} 仅接受纯数字，
 *       非法值按未设置（null）处理而不报错。</li>
 *   <li>首个 BOM（{@code \uFEFF}）会在解析前剥离；行内容按严格 UTF-8 解码，出现非法或不完整
 *       字节序列时按协议错误中断。</li>
 * </ol>
 *
 * <p>上限与错误映射：
 * <ul>
 *   <li>{@code maxLineBytes} 限制单行字节数，{@code maxEventBytes} 限制单个事件累计字节数（含字段间
 *       换行符），{@code maxTotalBytes} 限制整次读取的总字节数，{@code maxEvents} 限制派发的事件数量；
 *       任一项超出都以资源超限错误中断（见 {@link ExceptionUtils#responseTooLarge()}），避免被超长流
 *       拖垮内存。</li>
 *   <li>读取过程中逐字节通过 {@code call} 检查取消与剩余超时，命中即抛出对应的取消或超时错误。</li>
 *   <li>错误分为三类：超时与传输 IO 错误标记为可重试；资源超限与协议错误不建议重试；
 *       参数校验失败直接抛 {@link IllegalArgumentException}。</li>
 * </ul>
 *
 * <p>生命周期与线程安全：解析是阻塞的，直到输入流读到末尾、监听器返回 false，或触发上限/错误为止；
 * 输入流的关闭由调用方负责，本类不接管流的所有权。解析过程持有可变状态（由 {@link SseState} 承载），一次
 * {@link #parse} 调用对应一个独立状态；本类实例本身不可变、可复用，但不要多线程共享同一次解析调用。
 *
 * <p>调用方按以下顺序使用：先确保输入流已就绪，再调用 {@link #parse}，最后关闭输入流；监听器返回
 * {@code false} 时解析会提前结束并正常返回，已派发的事件数量由返回值给出。
 */
@Data
public final class SseParser {

    /**
     * 单行默认上限：64 KiB。
     *
     * <p>依据：SSE 规范中一行的典型形态是 {@code data: <JSON>}，大模型流式的增量块通常几十字节到几 KB；
     * 即使出现单行携带 Base64 片段（图片/视频类流式事件）的场景，64 KiB 也留有足够余量，同时足以挡住
     * 「一行无限增长」这类异常流。
     */
    public static final int DEFAULT_MAX_LINE_BYTES = 64 * 1024;

    /**
     * 单事件默认上限：1 MiB。
     *
     * <p>依据：一个事件由该事件块内的多行 {@code data} 拼接而成（多行之间还要加换行符），因此事件上限按
     * 行上限的一到两个数量级放宽；大模型流式里同一个事件通常只有一行，1 MiB 足以覆盖含较长 Base64 分片的
     * 事件，又不会让单次分配失控。
     */
    public static final int DEFAULT_MAX_EVENT_BYTES = 1024 * 1024;

    /**
     * 单次调用默认事件数上限：20 万个。
     *
     * <p>依据：OpenAI 兼容系与 Anthropic 都按 token 级增量推送，一次长回答可能产生数万个事件
     * （64K token 量级）。20 万留出充足余量，同时能在服务端异常持续推流时尽早按资源超限中断。
     */
    public static final int DEFAULT_MAX_EVENTS = 200_000;

    /**
     * 单次调用默认总字节上限：32 MiB。
     *
     * <p>依据：与传输配置里响应体上限同量级，避免解析器比传输层更早截断；按默认单行 64 KiB 估算，
     * 20 万事件的理论规模也在这个量级内。
     */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 32L * 1024 * 1024;

    /**
     * 事件名缺省值：{@code message}。
     *
     * <p>依据：SSE 规范规定未给出 {@code event} 字段时事件类型即 {@code message}。OpenAI 兼容系
     * （OpenAI、DeepSeek、火山方舟、通义等）只发 {@code data} 行，因此实际都走该缺省值。
     */
    public static final String DEFAULT_EVENT_TYPE = "message";

    /**
     * 单行最大字节数，单位字节，必须为正且不超过 {@code maxEventBytes}；超出按资源超限处理。
     * 无参构造器使用默认值 {@link #DEFAULT_MAX_LINE_BYTES}。
     */
    private final int maxLineBytes;
    /**
     * 单个事件累计最大字节数，单位字节，必须为正且不小于 {@code maxLineBytes}、不超过 {@code maxTotalBytes}。
     * 无参构造器使用默认值 {@link #DEFAULT_MAX_EVENT_BYTES}。
     */
    private final int maxEventBytes;
    /**
     * 单次调用允许派发的最大事件数，必须为正。无参构造器使用默认值 {@link #DEFAULT_MAX_EVENTS}。
     */
    private final int maxEvents;
    /**
     * 单次调用允许读取的总字节数，单位字节，必须为正且不小于 {@code maxEventBytes}。
     * 无参构造器使用默认值 {@link #DEFAULT_MAX_TOTAL_BYTES}；传输层通常传入自己的响应体上限。
     */
    private final long maxTotalBytes;

    /**
     * 使用各项默认上限创建解析器，适合绝大多数场景。
     *
     * <p>默认值：单行 {@value #DEFAULT_MAX_LINE_BYTES} 字节、单事件 {@value #DEFAULT_MAX_EVENT_BYTES} 字节、
     * 单次调用 {@value #DEFAULT_MAX_EVENTS} 个事件、总字节 {@value #DEFAULT_MAX_TOTAL_BYTES} 字节。
     * 需要与传输层响应体上限对齐（例如响应体上限小于 32 MiB）时，改用四参构造器显式指定。
     */
    public SseParser() {
        this(DEFAULT_MAX_LINE_BYTES, DEFAULT_MAX_EVENT_BYTES, DEFAULT_MAX_EVENTS, DEFAULT_MAX_TOTAL_BYTES);
    }

    /**
     * 创建解析器。
     *
     * @param maxLineBytes  单行最大字节数，单位字节，必须为正
     * @param maxEventBytes 单个事件最大字节数，单位字节，必须为正且不小于 maxLineBytes
     * @param maxEvents     单次调用允许派发的最大事件数，必须为正
     * @param maxTotalBytes 单次调用允许读取的总字节数，单位字节，必须为正且不小于 maxEventBytes
     * @throws IllegalArgumentException 任一项非正，或上限之间大小关系不合法时
     */
    public SseParser(int maxLineBytes, int maxEventBytes, int maxEvents, long maxTotalBytes) {
        if (maxLineBytes <= 0 || maxEventBytes <= 0 || maxEvents <= 0 || maxTotalBytes <= 0L) {
            throw new IllegalArgumentException("SSE limits must be positive");
        }
        // 上限必须逐级放宽：单行 <= 单事件 <= 总量，否则说明配置写错，直接拒绝
        if (maxLineBytes > maxEventBytes || maxEventBytes > maxTotalBytes) {
            throw new IllegalArgumentException("SSE limits must be ordered");
        }
        this.maxLineBytes = maxLineBytes;
        this.maxEventBytes = maxEventBytes;
        this.maxEvents = maxEvents;
        this.maxTotalBytes = maxTotalBytes;
    }

    /**
     * 从输入流解析 SSE 事件并逐个回调监听器。
     *
     * @param input    SSE 响应体输入流，非 null，由调用方负责关闭（本方法不会关闭它）
     * @param call     调用上下文，提供取消与超时检查，非 null
     * @param listener 事件监听器，非 null，返回 false 时停止解析
     * @return 已派发的事件数量，最小 0（监听器提前结束或流中无完整事件时）
     * @throws IllegalArgumentException 任一参数为 null 时
     * @throws ClaraException           取消、超时、非法 UTF-8、读取失败或超出各项上限时；其中超时与 IO
     *                                  错误标记为可重试，资源超限与协议错误不建议重试
     */
    public int parse(InputStream input, CallContext call, ISseListener listener) {
        if (input == null || call == null || listener == null) {
            throw new IllegalArgumentException("input, call and listener must not be null");
        }
        SseState state = new SseState();
        // 逐字节读取 + 复用一个行缓冲：只有遇到行结束符才解码，避免按字符或按行创建临时字符串
        ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maxLineBytes, 256));
        long total = 0L;
        // \r 之后若紧跟 \n，说明是 CRLF 的行结束符，需要把这个 \n 一并跳过
        boolean skipLf = false;
        // BOM 只可能出现在第一行行首
        boolean firstLine = true;
        try {
            int value;
            // 按字节读取而不使用字符解码流，以便精确控制行边界与字节上限
            while ((value = input.read()) >= 0) {
                checkContext(call);
                total++;
                if (total > maxTotalBytes) {
                    throw ExceptionUtils.responseTooLarge();
                }
                if (skipLf) {
                    skipLf = false;
                    if (value == '\n') {
                        continue;
                    }
                }
                if (value == '\r' || value == '\n') {
                    // 行结束：解码当前行缓冲，交给 accept 累积字段或在空行处派发事件
                    String decoded = StringUtils.decodeUtf8Strict(line.toByteArray());
                    line.reset();
                    // 仅首行需要剥离 UTF-8 BOM，其余行的 \ufeff 视作普通字符
                    if (firstLine && decoded.length() > 0 && decoded.charAt(0) == '\ufeff') {
                        decoded = decoded.substring(1);
                    }
                    firstLine = false;
                    // 返回 false 表示监听器要求提前结束流（如已收到完成标记）
                    if (!accept(decoded, state, listener)) {
                        return state.events;
                    }
                    if (value == '\r') {
                        skipLf = true;
                    }
                } else {
                    // 普通字节：先判断再加入缓冲，保证单行字节数不会超过上限
                    if (line.size() >= maxLineBytes) {
                        throw ExceptionUtils.responseTooLarge();
                    }
                    line.write(value);
                }
            }
            checkContext(call);
            // 流结束：按规范丢弃一切未收尾的内容——行尾缺少换行符的残行，以及未以空行收尾的事件块都不派发，
            // 也不对残行做 UTF-8 解码与体积校验（它根本不算完整一行）。
            // 代价是平台若漏发收尾空行，最后一块（例如带 finish_reason 或 usage 的块）会被丢掉；这是规范行为，
            // 宁可让上游按协议错误暴露出来，也不能把可能被截断的半截事件当成有效事件交给调用方。
            return state.events;
        } catch (ClaraException exception) {
            // 已是带错误码与重试语义的领域异常，直接向上抛出，不再包装
            throw exception;
        } catch (CharacterCodingException exception) {
            // 非法或不完整 UTF-8 字节序列：按协议错误处理
            throw ExceptionUtils.protocolError();
        } catch (SocketTimeoutException exception) {
            // 读取超时属于可重试的传输问题
            throw ExceptionUtils.requestTimeout();
        } catch (IOException exception) {
            // 连接中断等 IO 问题：同样交由上层按可重试错误处理
            throw ExceptionUtils.transportIoError();
        }
    }

    /**
     * 处理一行内容：累积字段值，遇到空行则派发事件。
     *
     * @param line     已解码的一行内容，不含换行符，非 null（可为空串）
     * @param state    当前解析状态，见 {@link SseState}，非 null
     * @param listener 事件监听器，非 null
     * @return 监听器的返回值，false 表示要求停止解析
     * @throws ClaraException 当前事件超过单事件上限或已派发事件数超过 maxEvents 时
     */
    private boolean accept(String line, SseState state, ISseListener listener) {
        // 空行是事件边界：派发已累积的事件块
        if (line.isEmpty()) {
            return dispatch(state, listener);
        }
        // 以冒号开头的是注释行（常用于心跳保活），按规范忽略且不计入事件体积
        if (line.charAt(0) == ':') {
            return true;
        }
        // 事件体积按字节统计；字段之间还有 1 个换行符，需要一并计入
        int lineBytes = StringUtils.utf8Length(line);
        int separatorBytes = state.hasField ? 1 : 0;
        // 先用减法判断再累加，避免加法溢出或先越界累加后才报错
        if (state.eventBytes > maxEventBytes - lineBytes - separatorBytes) {
            throw ExceptionUtils.responseTooLarge();
        }
        state.eventBytes += lineBytes + separatorBytes;
        state.hasField = true;
        // 以第一个冒号分隔字段名与字段值；无冒号时整行为字段名、值为空串
        int separator = line.indexOf(':');
        String field = separator < 0 ? line : line.substring(0, separator);
        String value = separator < 0 ? "" : line.substring(separator + 1);
        // 规范允许冒号后紧跟一个空格作为分隔，仅去掉这一个空格，其余空格属于字段值
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if ("data".equals(field)) {
            // 多行 data 之间用换行符拼接，构成一个完整事件数据
            if (state.hasData) {
                state.data.append('\n');
            }
            state.data.append(value);
            state.hasData = true;
        } else if ("event".equals(field)) {
            // event 是 SSE 规范中可选的事件名，用途是声明「本条消息属于哪类事件」，与事件内容（data）分离。
            // 是否出现取决于厂商：OpenAI 兼容系（OpenAI、DeepSeek、Azure OpenAI、火山引擎、通义等）只发 data 行，
            // 不会进入本分支，事件名由解析器兜底为 message；
            // Anthropic Messages 会发 message_start、content_block_delta、message_stop 等具名事件，OpenAI Responses API 会发 response.output_text.delta
            // 等，此时由各适配器按事件名分派不同类型的事件。
            state.event = value;
        } else if ("id".equals(field) && value.indexOf('\u0000') < 0) {
            // id 也是 SSE 规范字段：服务端用它给事件编号，客户端断线重连时会回传 Last-Event-ID 请求头，
            // 服务端据此从断点续推，也可用于事件去重与排序。格式为不含 NUL 的任意字符串，例如
            // "id: 42"、"id: evt-000123"；含 NUL 的值按规范忽略（即本分支条件里的判断）。
            // 大模型厂商基本不发送该字段：OpenAI 兼容系只用 data + [DONE]，Anthropic 只用 event + data。
            state.id = value;
        } else if ("retry".equals(field)) {
            // retry 同样是 SSE 规范字段：服务端用纯数字毫秒数声明断线后客户端应等待多久再重连，
            // 例如 "retry: 3000"。非纯数字（带符号、空格或其它字符）按规范忽略，因此这里只在纯数字时
            // 得到毫秒值，否则保持 null。浏览器 EventSource 会自动重连，Java 客户端不自动重连，
            // 本字段目前只做透传，不产生实际行为；大模型厂商流式响应通常也不发送该字段。
            state.retryMillis = NumberUtils.parseDigitsOrNull(value);
        }
        return true;
    }

    /**
     * 派发当前累积的事件并重置状态。
     *
     * @param state    当前解析状态，见 {@link SseState}，非 null
     * @param listener 事件监听器，非 null
     * @return 监听器的返回值；无 data 字段的事件按规范忽略并返回 true
     * @throws ClaraException 派发数量超过 maxEvents 时
     */
    private boolean dispatch(SseState state, ISseListener listener) {
        // 没有 data 字段的事件块按规范丢弃，只重置状态后继续解析
        if (!state.hasData) {
            state.resetEvent();
            return true;
        }
        // 事件数量上限：超出说明流长度异常，按资源超限中断
        if (state.events >= maxEvents) {
            throw ExceptionUtils.responseTooLarge();
        }
        state.events++;
        // event 缺失或为空时使用规范默认事件名 message
        SseEvent event = new SseEvent(
                state.event == null || state.event.isEmpty() ? DEFAULT_EVENT_TYPE : state.event,
                state.data.toString(), state.id, state.retryMillis);
        // 先重置再回调：事件对象已持有字段副本，状态可立即复用且不会被监听器读到后续事件的数据
        state.resetEvent();
        // 监听器返回 false 时停止后续解析（如已收到完整标记）
        return listener.onEvent(event);
    }

    /**
     * 检查是否已取消或已超时。
     *
     * @param call 调用上下文，非 null
     * @throws ClaraException 调用已取消或已超出截止时间时
     */
    private static void checkContext(CallContext call) {
        // 每个字节都检查一次，保证长时间流式读取时取消与超时能及时生效
        call.getCancellationToken().throwIfCancellationRequested(call.getTraceId());
        if (call.remainingMillis(System.currentTimeMillis()) <= 0L) {
            throw ExceptionUtils.requestTimeout();
        }
    }
}
