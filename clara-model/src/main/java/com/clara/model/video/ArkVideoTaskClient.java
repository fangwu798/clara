package com.clara.model.video;

import com.clara.core.media.IVideoGenerator;
import com.clara.core.media.VideoGenerationRequest;
import com.clara.core.media.VideoGenerationResult;
import com.clara.core.transport.IExecutionGuard;
import com.clara.core.transport.IHttpCall;
import com.clara.core.transport.IHttpTransport;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderCapabilityConfiguration;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.transport.http.JdkHttpTransport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 火山方舟视频生成任务客户端：提交异步任务并按配置轮询到终态。
 *
 * <p>协议要点：提交走 {@code endpoint}（{@code /contents/generations/tasks}），请求体为
 * {@code model + content[]}；任务状态走 {@code taskQueryEndpoint}（含 {@code {id}} 占位），
 * 成功后从 {@code content.video_url} 取视频地址。轮询间隔与次数来自能力节点的
 * {@code parameters.pollIntervalMillis / maxPollAttempts}，画面参数（比例、时长等）同样来自配置，
 * 并追加到提示词末尾的方舟命令行参数中。
 *
 * <p>线程安全：实例只持有不可变配置与无状态传输实现，可被多个工作流节点并发使用；每次调用创建独立的
 * HTTP 调用句柄。
 *
 * <p>失败语义：平台非 2xx、任务失败或轮询超时抛 {@link IllegalStateException}，消息包含 HTTP 状态码与
 * 平台返回的错误信息；不吞掉已产生的任务标识，便于用户排查。
 */
public final class ArkVideoTaskClient implements IVideoGenerator {

    /** 默认轮询间隔（毫秒）。 */
    private static final int DEFAULT_POLL_INTERVAL_MILLIS = 3_000;

    /** 默认最大轮询次数。 */
    private static final int DEFAULT_MAX_POLL_ATTEMPTS = 100;

    /** 方舟视频命令行参数：能力参数名到提示词参数名。 */
    private static final String[][] PROMPT_OPTIONS = {
            {"ratio", "--ratio"},
            {"duration", "--duration"},
            {"resolution", "--resolution"},
            {"fps", "--fps"},
            {"watermark", "--watermark"}
    };

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProviderConfiguration configuration;
    private final ModelConfig modelConfig;
    private final ProviderCapabilityConfiguration capability;
    private final IHttpTransport transport;
    private final int pollIntervalMillis;
    private final int maxPollAttempts;

    /**
     * 使用默认传输实现创建客户端。
     *
     * @param configuration 平台配置，不可为 null，且必须声明 VIDEO 能力
     * @throws IllegalArgumentException 配置为 null、未声明 VIDEO 能力或轮询参数非法时
     */
    public ArkVideoTaskClient(ProviderConfiguration configuration) {
        this(configuration, new JdkHttpTransport());
    }

    /**
     * 使用指定传输实现创建客户端（便于测试注入假传输）。
     *
     * @param configuration 平台配置，不可为 null，且必须声明 VIDEO 能力
     * @param transport     通用 HTTP 传输，不可为 null
     * @throws IllegalArgumentException 参数非法或轮询参数非法时
     */
    public ArkVideoTaskClient(ProviderConfiguration configuration, IHttpTransport transport) {
        if (configuration == null || transport == null) {
            throw new IllegalArgumentException("configuration and transport must not be null");
        }
        this.configuration = configuration;
        this.modelConfig = configuration.toConfig(CapabilityKindEnum.VIDEO);
        this.capability = configuration.getCapability(CapabilityKindEnum.VIDEO);
        this.transport = transport;
        this.pollIntervalMillis = positiveInt("pollIntervalMillis", DEFAULT_POLL_INTERVAL_MILLIS);
        this.maxPollAttempts = positiveInt("maxPollAttempts", DEFAULT_MAX_POLL_ATTEMPTS);
    }

    @Override
    public VideoGenerationResult generate(VideoGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        Consumer<String> progress = request.getProgress();
        Map<String, Object> body = submitBody(request);
        JsonNode submitted = execute(post(modelConfig.getEndpoint(), body), "提交视频任务");
        String taskId = firstText(submitted, "id", "task_id");
        if (taskId == null) {
            throw new IllegalStateException("提交视频任务失败：平台响应缺少任务标识");
        }
        notify(progress, "视频任务已提交（task=" + taskId + "），等待生成…");

        String lastStatus = null;
        for (int attempt = 1; attempt <= maxPollAttempts; attempt++) {
            JsonNode statusNode = execute(get(queryEndpoint(taskId)), "查询视频任务");
            String status = firstText(statusNode, "status", "state");
            if (!sameStatus(lastStatus, status)) {
                notify(progress, "视频任务状态：" + (status == null ? "unknown" : status));
                lastStatus = status;
            }
            List<String> urls = videoUrls(statusNode);
            if (isSuccess(status)) {
                if (urls.isEmpty()) {
                    throw new IllegalStateException("视频任务成功但未返回视频地址（task=" + taskId + "）："
                            + platformError(statusNode, "平台响应缺少 video_url"));
                }
                notify(progress, "视频生成完成");
                return new VideoGenerationResult(taskId, status, urls, usage(statusNode));
            }
            if (isFailure(status)) {
                throw new IllegalStateException("视频任务失败（task=" + taskId + "，状态=" + status + "）："
                        + platformError(statusNode, "平台未返回错误信息"));
            }
            sleep();
        }
        throw new IllegalStateException("视频任务轮询超时（task=" + taskId + "，已查询 " + maxPollAttempts
                + " 次，最后状态=" + (lastStatus == null ? "unknown" : lastStatus) + "）");
    }

    /**
     * 组装提交请求体：模型、文本提示词与参考图。
     */
    private Map<String, Object> submitBody(VideoGenerationRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelConfig.getModelId());
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(textItem(promptWithOptions(request.getPrompt())));
        int index = 0;
        for (String reference : request.getReferences()) {
            if (reference == null || reference.trim().isEmpty()) {
                continue;
            }
            content.add(imageItem(reference.trim(), index));
            index++;
        }
        body.put("content", content);
        return body;
    }

    /**
     * 把配置中的画面参数追加到提示词末尾；提示词已显式写了同名参数时不重复追加。
     */
    private String promptWithOptions(String prompt) {
        StringBuilder text = new StringBuilder(prompt);
        for (String[] option : PROMPT_OPTIONS) {
            Object value = capability.getParameters().get(option[0]);
            if (value == null || text.indexOf(option[1]) >= 0) {
                continue;
            }
            text.append(' ').append(option[1]).append(' ').append(value);
        }
        return text.toString();
    }

    private static Map<String, Object> textItem(String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "text");
        item.put("text", text);
        return item;
    }

    private static Map<String, Object> imageItem(String reference, int index) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("url", reference);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "image_url");
        item.put("image_url", image);
        item.put("role", index == 0 ? "first_frame" : "reference_image");
        return item;
    }

    /**
     * 发送 POST 请求；请求体序列化失败属于调用方数据问题。
     */
    private RawHttpRequest post(URI endpoint, Map<String, Object> body) {
        try {
            return new RawHttpRequest("POST", endpoint,
                    configuration.authorizationHeaders(CapabilityKindEnum.VIDEO, false, "application/json"),
                    JSON.writeValueAsBytes(body));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("视频任务请求体序列化失败：" + exception.getMessage(), exception);
        }
    }

    private RawHttpRequest get(URI endpoint) {
        return new RawHttpRequest("GET", endpoint,
                configuration.authorizationHeaders(CapabilityKindEnum.VIDEO, false, null), new byte[0]);
    }

    /**
     * 执行一次 HTTP 调用并按 JSON 解析；非 2xx 时把平台错误信息透出给用户。
     */
    private JsonNode execute(RawHttpRequest request, String action) {
        try (IHttpCall call = transport.newCall(request,
                configuration.getTransport().toHttpTransportPolicy(), IExecutionGuard.NONE)) {
            RawHttpResponse response = call.execute();
            String text = new String(response.getBody(), StandardCharsets.UTF_8);
            JsonNode root = parse(text);
            if (!response.isSuccessful()) {
                throw new IllegalStateException(action + "失败：HTTP " + response.getStatusCode()
                        + "：" + platformError(root, text));
            }
            if (root == null) {
                throw new IllegalStateException(action + "失败：平台返回空响应");
            }
            return root;
        }
    }

    private static JsonNode parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("视频平台响应不是合法 JSON：" + exception.getOriginalMessage(), exception);
        }
    }

    /**
     * 把任务查询端点里的 {@code {id}} 替换为任务标识；未声明占位时按 REST 习惯追加任务标识。
     */
    private URI queryEndpoint(String taskId) {
        String encoded = encode(taskId);
        String template = capability.requireTaskQueryEndpoint().toString()
                .replace("%7B", "{").replace("%7D", "}");
        if (template.contains("{id}")) {
            return URI.create(template.replace("{id}", encoded));
        }
        return URI.create(template.endsWith("/") ? template + encoded : template + "/" + encoded);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new IllegalStateException("任务标识编码失败", exception);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(pollIntervalMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("视频任务轮询被取消");
        }
    }

    private int positiveInt(String name, int fallback) {
        Object value = capability.getParameters().get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        int parsed = ((Number) value).intValue();
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static void notify(Consumer<String> progress, String message) {
        if (progress != null) {
            progress.accept(message);
        }
    }

    private static boolean sameStatus(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private static boolean isSuccess(String status) {
        return ("succeeded".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status));
    }

    private static boolean isFailure(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.toLowerCase(Locale.ROOT);
        return "failed".equals(normalized) || "cancelled".equals(normalized)
                || "canceled".equals(normalized) || "expired".equals(normalized);
    }

    /**
     * 从任务终态响应中提取全部视频地址，兼容 {@code content} / {@code data[]} / {@code output} 等常见形状。
     */
    private static List<String> videoUrls(JsonNode root) {
        Set<String> urls = new LinkedHashSet<>();
        collectUrls(root.get("content"), urls);
        collectUrls(root.get("output"), urls);
        collectUrls(root.get("result"), urls);
        collectUrls(root.get("data"), urls);
        collectUrls(root.get("videos"), urls);
        return new ArrayList<>(urls);
    }

    private static void collectUrls(JsonNode node, Set<String> urls) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            addUrl(node.textValue(), urls);
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectUrls(item, urls);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        collectUrls(node.get("video_url"), urls);
        collectUrls(node.get("url"), urls);
        JsonNode type = node.get("type");
        if (type != null && type.isTextual() && "video".equalsIgnoreCase(type.textValue())) {
            collectUrls(node.get("video"), urls);
        }
    }

    private static void addUrl(String value, Set<String> urls) {
        if (value == null) {
            return;
        }
        String candidate = value.trim();
        if (candidate.startsWith("http://") || candidate.startsWith("https://") || candidate.startsWith("data:")) {
            urls.add(candidate);
        }
    }

    private static Map<String, Object> usage(JsonNode root) {
        JsonNode usage = root.get("usage");
        if (usage == null || !usage.isObject()) {
            return new LinkedHashMap<>();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> values = JSON.convertValue(usage, LinkedHashMap.class);
        return values == null ? new LinkedHashMap<>() : values;
    }

    private static String firstText(JsonNode root, String first, String second) {
        String value = text(root, first);
        return value == null ? text(root, second) : value;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        if (value == null || !value.isTextual() || value.textValue().trim().isEmpty()) {
            return null;
        }
        return value.textValue().trim();
    }

    /**
     * 平台错误信息：优先 {@code error.code + error.message}，其次顶层 {@code message}，最后回退到原始响应体。
     */
    private static String platformError(JsonNode root, String fallback) {
        JsonNode error = root == null ? null : root.get("error");
        if (error != null && error.isObject()) {
            String code = text(error, "code");
            String message = text(error, "message");
            if (message != null) {
                return code == null ? message : code + "：" + message;
            }
        }
        String message = text(root, "message");
        if (message != null) {
            return message;
        }
        if (fallback == null || fallback.trim().isEmpty()) {
            return "平台未返回错误信息";
        }
        String trimmed = fallback.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500) + "…";
    }
}
