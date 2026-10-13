package com.clara.media.video;

import com.clara.context.ConvContext;
import com.clara.core.model.IRequest;
import com.clara.dto.model.RawHttpRequest;
import com.clara.dto.model.RawHttpResponse;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.api.execute.IHttpExecute;
import com.clara.kernel.config.ProviderCapabilityConfiguration;
import com.clara.kernel.config.ProviderConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 文生视频客户端：按平台 {@code capabilities.VIDEO} 声明提交异步任务并轮询，直到取回视频地址。
 *
 * <p>是什么、在哪一层：application 媒体层（{@code com.clara.media.video}）的组件，服务于「主模型经
 * {@code run_command} 调用 clara.jar 生视频」的场景。它复用平台配置里的端点、异步查询端点、模型 ID、
 * 凭据与传输白名单，不自带平台分支。
 *
 * <p>为什么需要：火山方舟等平台的视频生成是异步任务——先提交得到一个任务标识，再按标识轮询状态，成功后
 * 才拿到视频地址。这一「提交 + 轮询」不在单次请求-响应链路内，故独立成本类，避免改动既有编解码链路。
 *
 * <p>执行流程：读取 {@code VIDEO} 能力配置（{@code endpoint} 为提交地址、{@code taskQueryEndpoint} 为
 * 查询地址）→ 提交 {@code {"model":…,"content":[{"type":"text","text":prompt}]}} → 取回任务标识 →
 * 按 {@code pollIntervalMillis} 间隔轮询查询地址 {@code <taskQueryEndpoint>/<id>}，最多
 * {@code maxPollAttempts} 次 → 状态为 {@code succeeded} 时返回 {@code content.video_url}。
 *
 * <p>失败语义：能力未配置、缺少查询端点或模型 ID 时抛配置异常；提交/查询非 2xx、响应结构非法、任务
 * {@code failed}/{@code cancelled} 或超出轮询次数时抛 {@link IllegalStateException}。
 */
public final class VideoGenerationClient {

    /**
     * JSON 映射器，用于拼装提交体与解析响应。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 本客户端使用的能力种类。
     */
    private static final CapabilityKindEnum CAPABILITY = CapabilityKindEnum.VIDEO;

    /**
     * 默认轮询间隔（毫秒）。
     */
    private static final int DEFAULT_POLL_INTERVAL_MILLIS = 3_000;

    /**
     * 默认最大轮询次数。
     */
    private static final int DEFAULT_MAX_POLL_ATTEMPTS = 100;

    /**
     * 单次提示词允许的最大字符数（超出即拒绝）。
     */
    private static final int MAX_PROMPT_CHARS = 32_000;

    /**
     * 网络传输实现，负责端点白名单、超时与体积上限。
     */
    private final IHttpExecute transport;

    /**
     * 创建客户端。
     *
     * @param transport 传输实现，不可为 null
     * @throws IllegalArgumentException transport 为 null 时
     */
    public VideoGenerationClient(IHttpExecute transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.transport = transport;
    }

    /**
     * 提交文生视频任务并轮询到完成，返回视频地址。
     *
     * @param configuration 平台配置，须已配置 {@code VIDEO} 能力，不可为 null
     * @param prompt        画面描述，不可为空白
     * @param progress      过程日志接收方（如提交、轮询进度），可为 null（不输出）
     * @return 视频地址（http/https），非 null
     * @throws IllegalArgumentException 参数非法或提示词超长时
     * @throws IllegalStateException    提交/查询失败、响应结构非法、任务失败或轮询超时
     */
    public String generate(ProviderConfiguration configuration, String prompt, Consumer<String> progress) {
        Consumer<String> log = progress == null ? message -> { } : progress;
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        String text = prompt.trim();
        if (text.length() > MAX_PROMPT_CHARS) {
            throw new IllegalArgumentException("提示词超过上限 " + MAX_PROMPT_CHARS + " 字符");
        }
        ProviderCapabilityConfiguration capability = configuration.getCapability(CAPABILITY);
        String model = capability.requireModelId();
        URI submitEndpoint = capability.getEndpoint();
        URI queryEndpoint = capability.requireTaskQueryEndpoint();
        log.accept("[info] 提交视频生成任务：model=" + model);

        try (ConvContext.Scope ignored = ConvContext.open(
                new ConvContext("media-cli", "video-" + System.currentTimeMillis(), configuration, CAPABILITY))) {
            RawHttpResponse created = transport.execute(fixed(new RawHttpRequest("POST", submitEndpoint,
                    configuration.authorizationHeaders(CAPABILITY, false), submitBody(model, text))));
            String taskId = requiredText(parse(created, "提交视频生成任务"), "id");
            log.accept("[info] 任务已提交：taskId=" + taskId);
            return poll(configuration, queryEndpoint, taskId, capability, log);
        }
    }

    /**
     * 按配置的间隔与次数轮询任务状态，成功时返回视频地址。
     */
    private String poll(ProviderConfiguration configuration, URI queryEndpoint, String taskId,
            ProviderCapabilityConfiguration capability, Consumer<String> progress) {
        int interval = intParameter(capability, "pollIntervalMillis", DEFAULT_POLL_INTERVAL_MILLIS);
        int maxAttempts = intParameter(capability, "maxPollAttempts", DEFAULT_MAX_POLL_ATTEMPTS);
        URI pollEndpoint = URI.create(trimTrailingSlash(queryEndpoint.toString()) + "/" + taskId);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            sleep(interval);
            RawHttpResponse response = transport.execute(fixed(new RawHttpRequest("GET", pollEndpoint,
                    configuration.authorizationHeaders(CAPABILITY, false, null), null)));
            JsonNode status = parse(response, "查询视频生成任务");
            String value = text(status, "status");
            progress.accept("[info] 视频任务轮询中（第 " + attempt + "/" + maxAttempts + " 次）：status=" + value);
            if ("succeeded".equals(value)) {
                return requiredText(status.path("content"), "video_url");
            }
            if ("failed".equals(value) || "cancelled".equals(value)) {
                throw new IllegalStateException("视频任务" + value + "：" + failureMessage(status));
            }
        }
        throw new IllegalStateException("视频任务在 " + maxAttempts + " 次轮询后仍未完成（可上调参数 maxPollAttempts）");
    }

    /**
     * 拼装提交体：{@code {"model":…,"content":[{"type":"text","text":…}]}}。
     */
    private static byte[] submitBody(String model, String prompt) {
        Map<String, Object> textContent = new LinkedHashMap<String, Object>();
        textContent.put("type", "text");
        textContent.put("text", prompt);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("content", java.util.Collections.singletonList(textContent));
        try {
            return JSON.writeValueAsBytes(body);
        } catch (IOException failure) {
            throw new IllegalStateException("视频请求体序列化失败：" + failure.getMessage(), failure);
        }
    }

    /**
     * 解析响应：非 2xx 提取平台提示后失败；2xx 解析为 JSON 树。
     */
    private static JsonNode parse(RawHttpResponse response, String action) {
        byte[] body = response.getBody();
        String text = body.length == 0 ? "" : new String(body, StandardCharsets.UTF_8);
        int statusCode = response.getStatusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException(action + "失败：HTTP " + statusCode + providerMessage(text));
        }
        try {
            return text.trim().isEmpty() ? JSON.createObjectNode() : JSON.readTree(text);
        } catch (IOException failure) {
            throw new IllegalStateException(action + "响应不是合法 JSON：" + text, failure);
        }
    }

    /**
     * 从平台错误响应体中提取 {@code error.message}，用于失败提示。
     */
    private static String providerMessage(String body) {
        try {
            JsonNode message = JSON.readTree(body).path("error").path("message");
            if (message.isTextual()) {
                return "：" + message.asText();
            }
        } catch (Exception ignored) {
            // 响应体非预期结构时不附带平台提示
        }
        return "";
    }

    private static String failureMessage(JsonNode node) {
        JsonNode message = node.path("error").path("message");
        return message.isTextual() ? message.asText() : "未提供错误详情";
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().trim().isEmpty() ? value.asText() : null;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalStateException("视频任务响应缺少字段 " + field);
        }
        return value;
    }

    private static int intParameter(ProviderCapabilityConfiguration capability, String name, int fallback) {
        Object value = capability.getParameters().get(name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return fallback;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("视频轮询被中断", interrupted);
        }
    }

    /**
     * 用已构造好的线格式请求包装成 {@link IRequest}，复用传输层的白名单、超时与错误处理。
     */
    @SuppressWarnings("unchecked")
    private static IRequest fixed(RawHttpRequest request) {
        return new IRequest() {
            @Override
            public <T extends IRequest> Class<T> getCls() {
                return (Class<T>) IRequest.class;
            }

            @Override
            public RawHttpRequest toRequest() {
                return request;
            }

            @Override
            public Boolean getStream() {
                return Boolean.FALSE;
            }
        };
    }
}
