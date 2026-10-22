package com.clara.orchestration;

import com.clara.dto.memory.ContextControlConfig;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCodes;

import java.util.Locale;
import java.util.Map;

/**
 * 上下文超限恢复策略：识别「超限」类失败，并给出压缩后的上下文控制配置。
 *
 * <p>为什么需要：平台对上下文长度、请求体积的限制各不相同，命中时直接失败会让用户丢掉整轮已完成的工具
 * 结果。编排层捕获这类失败后，用更小的窗口与工具预算重新装配上下文，再发送同一个请求，即可在不换模型、
 * 不重跑工具的前提下继续本轮。
 *
 * <p>识别口径：
 * <ul>
 *   <li>{@code MODEL_REQUEST_TOO_LARGE} / {@code MODEL_RESPONSE_TOO_LARGE}：本地体积上限，直接视为超限；</li>
 *   <li>{@code MODEL_PROVIDER_FAILED}：仅当状态码为 400 / 413，且平台提示包含上下文超限关键词时成立，
 *       避免把参数错误、鉴权失败误判成超限。</li>
 * </ul>
 *
 * <p>恢复强度按次数递增：第一次保留 24 条消息 / 256KB / 单条 800 字；第二次收紧到 12 条 / 128KB /
 * 单条 400 字。压缩只影响本次下发，不改写已持久化的会话历史。
 *
 * <p>线程安全：无状态，全部为静态方法。
 */
final class ContextOverflowPolicy {

    /**
     * 单轮最多触发的超限恢复次数。
     */
    static final int MAX_RETRIES = 2;

    private static final int MESSAGES_FIRST = 24;
    private static final int MESSAGES_SECOND = 12;
    private static final long BYTES_FIRST = 256L * 1024L;
    private static final long BYTES_SECOND = 128L * 1024L;
    private static final int TOOL_CHARS_FIRST = 800;
    private static final int TOOL_CHARS_SECOND = 400;
    /**
     * 响应体超限后的单次输出上限。不能压到 4000 这类很小的值：推理模型的思维链同样计入输出，
     * 预算太小时思维链会吃掉全部额度，模型来不及给出正文或发起工具调用。
     */
    private static final int OUTPUT_TOKENS_FIRST = 32768;
    private static final int OUTPUT_TOKENS_SECOND = 16384;

    /**
     * 平台提示中常见的上下文超限关键词，统一按小写匹配。
     */
    private static final String[] OVERFLOW_KEYWORDS = {
        "context length",
        "context_length",
        "maximum context",
        "too many tokens",
        "token limit",
        "prompt is too long",
        "input is too long",
        "input length",
        "request too large",
        "exceeds the maximum",
    };

    private ContextOverflowPolicy() {
    }

    /**
     * 判断一次失败是否属于「上下文 / 请求体积超限」。
     *
     * @param failure 模型调用失败，可为 null
     * @return 属于超限类失败时返回 true
     */
    static boolean matches(ClaraException failure) {
        if (failure == null || failure.getError() == null) {
            return false;
        }
        ClaraError error = failure.getError();
        String code = error.getCode();
        if (ErrorCodes.MODEL_REQUEST_TOO_LARGE.equals(code)
                || ErrorCodes.MODEL_RESPONSE_TOO_LARGE.equals(code)) {
            return true;
        }
        if (!ErrorCodes.MODEL_PROVIDER_FAILED.equals(code) || !isClientSizeStatus(error.getProviderCode())) {
            return false;
        }
        return containsOverflowKeyword(providerMessage(error));
    }

    /**
     * 是否为响应体超限（输出侧失控），用于决定是否同时收紧单次输出上限。
     *
     * @param failure 模型调用失败，可为 null
     * @return 错误码为 {@code MODEL_RESPONSE_TOO_LARGE} 时返回 true
     */
    static boolean isResponseTooLarge(ClaraException failure) {
        return failure != null && failure.getError() != null
                && ErrorCodes.MODEL_RESPONSE_TOO_LARGE.equals(failure.getError().getCode());
    }

    /**
     * 生成第 {@code attempt} 次恢复使用的上下文控制配置。
     *
     * <p>只收紧窗口与工具输出预算，保留调用方原有的压缩、向量化、检索设置；未配置的维度补上恢复上限。
     *
     * @param source  原始配置，可为 null（使用默认配置）
     * @param attempt 第几次恢复，从 1 开始
     * @return 压缩后的配置，非 null
     */
    static ContextControlConfig compactControl(ContextControlConfig source, int attempt) {
        ContextControlConfig base = source == null ? ContextControlConfig.defaults() : source;
        boolean first = attempt <= 1;
        int messageLimit = first ? MESSAGES_FIRST : MESSAGES_SECOND;
        long byteLimit = first ? BYTES_FIRST : BYTES_SECOND;
        int toolChars = first ? TOOL_CHARS_FIRST : TOOL_CHARS_SECOND;
        return ContextControlConfig.builder()
                .contextWindowTokens(base.getContextWindowTokens())
                .windowMaxMessages(limit(base.getWindowMaxMessages(), messageLimit))
                .windowMaxSizeBytes(limit(base.getWindowMaxSizeBytes(), byteLimit))
                .windowMaxTokensPercent(base.getWindowMaxTokensPercent())
                .recentTurnsKept(base.getRecentTurnsKept())
                .compressEnabled(base.isCompressEnabled())
                .vectorizeEnabled(base.isVectorizeEnabled())
                .retrievalCount(base.getRetrievalCount())
                .similarityThreshold(base.getSimilarityThreshold())
                .toolOutputMaxChars(limit(base.getToolOutputMaxChars(), toolChars))
                .build();
    }

    /**
     * 响应体超限时第 {@code attempt} 次恢复使用的单次输出 token 上限；其它超限返回 null（不覆盖）。
     *
     * @param failure 模型调用失败，非 null
     * @param attempt 第几次恢复，从 1 开始
     * @return 输出上限；不需要覆盖时为 null
     */
    static Integer outputTokens(ClaraException failure, int attempt) {
        return outputTokens(failure, attempt, null);
    }

    /**
     * 响应体超限时第 {@code attempt} 次恢复使用的单次输出 token 上限，并保证不超过 {@code ceiling}。
     *
     * @param failure 模型调用失败，非 null
     * @param attempt 第几次恢复，从 1 开始
     * @param ceiling 本次调用允许的输出上限；为 null 时使用恢复默认值
     * @return 输出上限；不需要覆盖时为 null
     */
    static Integer outputTokens(ClaraException failure, int attempt, Integer ceiling) {
        if (!isResponseTooLarge(failure)) {
            return null;
        }
        int tokens = attempt <= 1 ? OUTPUT_TOKENS_FIRST : OUTPUT_TOKENS_SECOND;
        return ceiling == null ? tokens : Math.min(ceiling, tokens);
    }

    private static boolean isClientSizeStatus(String status) {
        return "400".equals(status) || "413".equals(status);
    }

    private static String providerMessage(ClaraError error) {
        Map<String, String> details = error.getSafeDetails();
        return details == null ? null : details.get("providerMessage");
    }

    private static boolean containsOverflowKeyword(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String keyword : OVERFLOW_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static int limit(int configured, int ceiling) {
        return configured > 0 ? Math.min(configured, ceiling) : ceiling;
    }

    private static long limit(long configured, long ceiling) {
        return configured > 0 ? Math.min(configured, ceiling) : ceiling;
    }
}
