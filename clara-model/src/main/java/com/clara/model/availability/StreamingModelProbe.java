package com.clara.model.availability;

import com.clara.utils.StringUtils;
import com.clara.context.ConvContext;
import com.clara.core.model.IStreamHandle;
import com.clara.dto.model.StreamEvent;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.FinishReasonEnum;
import com.clara.enums.StreamEventEnum;
import com.clara.exception.ClaraError;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.http.execute.Jdk8HttpTransport;
import com.clara.kernel.message.delta.UserContent;
import com.clara.model.availability.api.IModelProbe;
import com.clara.model.provider.CompatibleStream;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于流式响应首个非空正文增量的模型可用性探测器。
 *
 * <p>探测器发送一条限制输出内容的最小对话请求，从真正发起请求前开始计时。收到首个非空
 * {@link StreamEventEnum#TEXT_DELTA} 时判定模型可用，并记录首字符响应时间；推理模型在探测预算内只产出
 * {@link StreamEventEnum#REASONING_DELTA} 时同样判定可用——平台、凭据与模型都已确认可用，未产出正文只是
 * 预算被思维链占满，此时以思维链首字时间作为响应时间并在结果中说明；收到错误、响应正常结束但无任何模型输出、
 * 请求超时或调用抛出异常时判定模型不可用。探测结束后会取消仍未完成的流，避免继续生成无用内容。
 *
 * <p>本实现不持有可变实例状态，可被多个启动检查流程复用。每次调用使用独立的会话上下文、同步信号和结果引用。
 */
public final class StreamingModelProbe implements IModelProbe {

    /** 探测请求提示词，要求模型只生成一个固定字符，以减少响应耗时和 token 消耗。 */
    private static final String PROBE_PROMPT = "只输出数字 1，不要输出其他内容。";

    /**
     * 探测请求的输出 token 上限。
     *
     * <p>为什么不能取更小值：推理模型的思维链同样计入输出 token，预算过小会只产出思维链、正文为空并以
     * {@code LENGTH} 结束，从而把可用的模型误判为不可用（如 DeepSeek 思考模式在 16 个 token 下必然如此）。
     * 该值只需覆盖「一段简短推理加一个字符」；探测在取得首个正文字符后立即取消流，正常路径不会跑满预算。
     */
    private static final int PROBE_MAX_TOKENS = 512;

    /**
     * 发起一次最小流式对话请求并测量首个非空正文字符的响应时间。
     *
     * <p>该方法会为本次探测创建独立的 {@link ConvContext}。等待达到超时时间或流进入可判定状态后，
     * 会取消仍在运行的请求。线程等待中断时会恢复当前线程的中断标记。
     *
     * @param path          被检查模型的供应商配置文件路径
     * @param alias         模型别名，用于构造探测会话标识及检查结果
     * @param provider      已加载的供应商配置，包含凭据、传输参数和能力定义
     * @param chat          从供应商配置解析出的对话能力配置
     * @param timeoutMillis 等待首字符或终态事件的最长时间，单位为毫秒
     * @return 模型可用时包含首字符响应时间，否则包含不可用原因的检查结果
     */
    @Override
    public ModelCheckResult probe(Path path, String alias, ProviderConfiguration provider, ModelConfig chat, long timeoutMillis) {
        Observation observation = new Observation(System.nanoTime());
        HttpRequest request = HttpRequest.builder()
                .model(chat.getModelId())
                .messages(Collections.singletonList(new UserContent(PROBE_PROMPT)))
                .maxTokens(PROBE_MAX_TOKENS)
                .stream(Boolean.TRUE)
                .build();
        IStreamHandle handle = null;
        try (ConvContext.Scope ignored = ConvContext.open(new ConvContext(null, "startup-model-check-" + alias, provider, CapabilityKindEnum.CHAT))) {
            handle = new CompatibleStream(new Jdk8HttpTransport()).chat(request, HttpResponse.class, observation::accept);
            if (!observation.await(timeoutMillis)) {
                observation.fail("检查超时（" + timeoutMillis + "ms）");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            observation.fail("检查被中断");
        } catch (RuntimeException exception) {
            observation.fail(concise(exception));
        } finally {
            if (handle != null && !handle.isDone()) {
                handle.cancel();
            }
        }
        return observation.result(path, alias, provider.getProvider().name(), chat.getModelId());
    }

    /**
     * 单次探测的观测状态：首个正文与思维链增量的响应时间、失败原因、终态响应。
     *
     * <p>为什么集中成一个对象：探测回调需要同时累积多份互不相同的状态，集中在一起可以让
     * {@link #probe} 只负责编排，回调也不必携带一长串参数。
     */
    private static final class Observation {

        /** 发起探测时的单调时钟纳秒值，用于计算响应时间。 */
        private final long started;

        /** 判定信号：取得首个正文增量、收到终态事件或失败时放行等待线程。 */
        private final CountDownLatch finished = new CountDownLatch(1);

        /** 首个非空正文字符的响应时间，尚未取得时为 -1。 */
        private final AtomicLong firstContent = new AtomicLong(-1L);

        /** 首个非空思维链增量的响应时间，尚未取得时为 -1。 */
        private final AtomicLong firstReasoning = new AtomicLong(-1L);

        /** 失败原因，先到者胜出；尚未失败时为 null。 */
        private final AtomicReference<String> failure = new AtomicReference<>();

        /** 终态响应，用于在缺少正文时补充结束原因；未收到终态事件时为 null。 */
        private final AtomicReference<HttpResponse> terminal = new AtomicReference<>();

        /**
         * 创建观测状态。
         *
         * @param started 发起探测时的单调时钟纳秒值
         */
        Observation(long started) {
            this.started = started;
        }

        /**
         * 等待探测进入可判定状态。
         *
         * @param timeoutMillis 最长等待时间，单位为毫秒
         * @return 取得正文或收到终态事件时返回 true，等待超时返回 false
         * @throws InterruptedException 等待过程中线程被中断时
         */
        boolean await(long timeoutMillis) throws InterruptedException {
            return finished.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        /**
         * 记录失败原因；已有原因时保留先到的原因。
         *
         * @param reason 失败原因，非 null
         */
        void fail(String reason) {
            failure.compareAndSet(null, reason);
        }

        /**
         * 处理模型流事件。
         *
         * <p>非空正文增量记录首次出现的时间并结束等待；非空思维链增量只记录时间，是否据此判定可用交给
         * {@link #result}；错误事件记录可安全展示的原因；终态事件保存累积响应并结束等待。
         * 其他流事件不会影响探测结果。
         *
         * @param event 当前流事件，非 null
         */
        void accept(StreamEvent event) {
            StreamEventEnum type = event.getType();
            if (type == StreamEventEnum.TEXT_DELTA) {
                if (!StringUtils.isBlank(event.getPayload())) {
                    firstContent.compareAndSet(-1L, elapsed());
                    finished.countDown();
                }
            } else if (type == StreamEventEnum.REASONING_DELTA) {
                if (!StringUtils.isBlank(event.getPayload())) {
                    firstReasoning.compareAndSet(-1L, elapsed());
                }
            } else if (type == StreamEventEnum.ERROR) {
                fail(describe(event.getError()));
                finished.countDown();
            } else if (type == StreamEventEnum.RESPONSE_COMPLETE) {
                if (event.getResponse() instanceof HttpResponse) {
                    terminal.set((HttpResponse) event.getResponse());
                }
                finished.countDown();
            }
        }

        /**
         * 汇总本次探测结果。
         *
         * <p>判定顺序：正文、失败原因、思维链。只产出思维链同样判定可用——响应时间取思维链首字时间，
         * 并在结果中说明未产出正文的原因；既没有正文也没有思维链时，用终态响应的结束原因补充说明。
         *
         * @param path     供应商配置文件路径
         * @param alias    模型别名
         * @param provider 供应商名称
         * @param model    模型 ID
         * @return 检查结果，非 null
         */
        ModelCheckResult result(Path path, String alias, String provider, String model) {
            if (firstContent.get() >= 0L) {
                return ModelCheckResult.available(path, alias, provider, model, firstContent.get());
            }
            String failed = failure.get();
            if (failed != null) {
                return ModelCheckResult.unavailable(path, alias, provider, model, failed);
            }
            if (firstReasoning.get() >= 0L) {
                return ModelCheckResult.available(path, alias, provider, model, firstReasoning.get(),
                        "推理模型仅返回思维链，未在探测预算内产出正文");
            }
            return ModelCheckResult.unavailable(path, alias, provider, model, "响应结束但没有正文" + finishReasonSuffix());
        }

        /**
         * 取「结束原因」后缀，无终态响应或未解析出结束原因时返回空串。
         *
         * @return 形如 {@code （结束原因=LENGTH）} 的后缀，或空串
         */
        private String finishReasonSuffix() {
            HttpResponse response = terminal.get();
            FinishReasonEnum finishReason = response == null ? null : response.getFinishReason();
            return finishReason == null ? "" : "（结束原因=" + finishReason + "）";
        }

        /**
         * 取当前时刻相对探测发起的毫秒数。
         *
         * @return 已耗时，单位为毫秒
         */
        private long elapsed() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        }
    }

    /**
     * 把流错误事件转成适合写入检查结果的失败原因，并附上平台返回的错误提示。
     *
     * <p>为什么要附平台提示：状态码只能说明「403」，而「余额不足 / 参数非法」这类真实原因在
     * {@link ClaraError#getSafeDetails()} 的 {@code providerMessage} 里，附上后启动检查即可直接定位问题。
     *
     * @param error 错误事件携带的领域错误，可为 null
     * @return 面向使用者的单行失败原因
     */
    private static String describe(ClaraError error) {
        if (error == null) {
            return "模型返回错误";
        }
        String message = error.getSafeMessage();
        String providerMessage = error.getSafeDetails().get("providerMessage");
        return StringUtils.isBlank(providerMessage) ? message : message + "：" + providerMessage;
    }

    /**
     * 把调用异常压缩为适合写入检查结果的单行说明。
     *
     * @param failure 模型请求过程中捕获的异常
     * @return 异常类型以及非空异常消息组成的简短文本
     */
    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (StringUtils.isBlank(message) ? "" : ": " + message);
    }

    /**
     * 判断字符串是否为 null、空串或只包含空白字符。
     *
     * @param value 待检查字符串
     * @return 字符串没有有效内容时返回 true
     */
}
