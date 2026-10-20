package com.clara.model.embedding;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.exception.HttpTransportException;
import com.clara.core.transport.HttpTransportPolicy;
import com.clara.core.transport.IExecutionGuard;
import com.clara.core.transport.IHttpCall;
import com.clara.core.transport.IHttpTransport;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import com.clara.core.transport.sse.SseParserConfig;
import com.clara.transport.http.HostEndpointPolicy;
import com.clara.transport.http.JdkHttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 OpenAI 兼容 {@code /embeddings} 接口的嵌入客户端，实现 {@link IEmbeddingClient} 契约。
 *
 * <p>是什么、在哪一层：{@code clara-model} 的嵌入 API 适配实现（{@code com.clara.model.embedding}）。火山方舟、
 * Kimi（月之暗面）等平台的嵌入接口均为 OpenAI 兼容形状（请求 {@code {"model":…,"input":…}}，响应
 * {@code data[0].embedding}），DeepSeek 若开放嵌入接口也同形状，因此共用一份实现；端点与模型 ID 由调用方
 * 传入。无真实凭据或离线环境请改用 {@link HashingEmbeddingClient} 占位实现。
 *
 * <p>密钥安全：API Key 仅在构造时写入字段、发送时拼入 {@code Authorization} 头，本类任何路径都不会打印或
 * 抛出密钥值；错误信息只包含状态码与响应体。
 *
 * <p>失败语义：参数非法抛 {@link IllegalArgumentException}；网络失败、非 2xx 或响应结构不符时抛
 * {@link IllegalStateException}（原因链保留底层异常）。失败即抛异常，不做重试。
 */
public final class HttpEmbeddingClient implements IEmbeddingClient {

    /**
     * 默认连接超时毫秒数。
     */
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10000;
    /**
     * 默认读取超时毫秒数。
     */
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 60000;
    /**
     * 嵌入请求体最大字节数。
     */
    private static final int DEFAULT_MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    /**
     * 嵌入响应体最大字节数。
     */
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    /**
     * 嵌入接口端点，必须是绝对 HTTP(S) 地址。
     */
    private final URI endpoint;
    /**
     * 嵌入模型 ID。
     */
    private final String modelId;
    /**
     * API Key，仅用于拼入鉴权头，绝不打印。
     */
    private final String apiKey;
    /**
     * 请求 / 响应体 JSON 解析器。
     */
    private final ObjectMapper json;
    /**
     * 通用 HTTP 传输实现。
     */
    private final IHttpTransport httpTransport;
    /**
     * 嵌入接口的传输策略。
     */
    private final HttpTransportPolicy transportPolicy;

    /**
     * 使用默认超时创建嵌入客户端。
     *
     * @param endpoint 嵌入接口端点（如 {@code https://api.moonshot.cn/v1/embeddings}），不可为 null
     * @param modelId  嵌入模型 ID，不可为空白
     * @param apiKey   平台 API Key，不可为空
     */
    public HttpEmbeddingClient(URI endpoint, String modelId, String apiKey) {
        this(endpoint, modelId, apiKey, DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    /**
     * 创建嵌入客户端。
     *
     * @param endpoint             嵌入接口端点，不可为 null
     * @param modelId              嵌入模型 ID，不可为空白
     * @param apiKey               平台 API Key，不可为空
     * @param connectTimeoutMillis 连接超时毫秒数，必须为正
     * @param readTimeoutMillis    读取超时毫秒数，必须为正
     * @throws IllegalArgumentException 任一参数非法时
     */
    public HttpEmbeddingClient(URI endpoint, String modelId, String apiKey,
            int connectTimeoutMillis, int readTimeoutMillis) {
        if (endpoint == null || !endpoint.isAbsolute() || endpoint.getHost() == null
                || !("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI");
        }
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
        this.endpoint = endpoint;
        this.modelId = modelId;
        this.apiKey = apiKey;
        this.json = new ObjectMapper();
        boolean allowHttp = "http".equalsIgnoreCase(endpoint.getScheme());
        this.httpTransport = new JdkHttpTransport();
        this.transportPolicy = new HttpTransportPolicy(
                new HostEndpointPolicy(Collections.singleton(endpoint.getHost()), allowHttp),
                connectTimeoutMillis, readTimeoutMillis,
                DEFAULT_MAX_REQUEST_BYTES, DEFAULT_MAX_RESPONSE_BYTES,
                Collections.emptyMap(),
                new SseParserConfig(SseParserConfig.DEFAULT_MAX_LINE_BYTES,
                        SseParserConfig.DEFAULT_MAX_EVENT_BYTES,
                        SseParserConfig.DEFAULT_MAX_EVENTS,
                        DEFAULT_MAX_RESPONSE_BYTES, false));
    }

    /**
     * 创建用于装配参数的建造器；未显式指定的参数会回退到系统属性 / 环境变量，便于从外部配置注入
     * 端点、模型名与 API Key。
     *
     * @return 建造器，非 null
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link HttpEmbeddingClient} 的建造器：显式注入 {@code endpoint}（或 {@code baseUrl}）、
     * {@code model}、{@code apiKey}，未注入时从系统属性 / 环境变量读取默认值。
     */
    public static final class Builder {

        private URI endpoint;
        private String baseUrl;
        private String modelId;
        private String apiKey;
        private int connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
        private int readTimeoutMillis = DEFAULT_READ_TIMEOUT_MILLIS;

        /**
         * 直接指定嵌入接口端点（完整的 {@code /embeddings} 地址）。
         *
         * @param endpoint 嵌入端点，不可为 null
         * @return 本建造器
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = URI.create(endpoint);
            return this;
        }

        /**
         * 指定平台基础地址；不以 {@code /embeddings} 结尾时自动补全。
         *
         * @param baseUrl 平台基础地址，如 {@code https://api.moonshot.cn/v1}
         * @return 本建造器
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * 指定嵌入模型 ID。
         *
         * @param modelId 模型 ID
         * @return 本建造器
         */
        public Builder model(String modelId) {
            this.modelId = modelId;
            return this;
        }

        /**
         * 指定平台 API Key。
         *
         * @param apiKey API Key
         * @return 本建造器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 指定连接超时毫秒数。
         *
         * @param millis 连接超时毫秒数，必须为正
         * @return 本建造器
         */
        public Builder connectTimeoutMillis(int millis) {
            this.connectTimeoutMillis = millis;
            return this;
        }

        /**
         * 指定读取超时毫秒数。
         *
         * @param millis 读取超时毫秒数，必须为正
         * @return 本建造器
         */
        public Builder readTimeoutMillis(int millis) {
            this.readTimeoutMillis = millis;
            return this;
        }

        /**
         * 汇总参数并创建嵌入客户端。
         *
         * <p>未显式指定的值依次回退到系统属性与同名环境变量：{@code clara.embedding.baseUrl} /
         * {@code CLARA_EMBEDDING_BASE_URL}、{@code clara.embedding.model} / {@code CLARA_EMBEDDING_MODEL}、
         * {@code clara.embedding.apiKey} / {@code CLARA_EMBEDDING_API_KEY}。
         *
         * @return 嵌入客户端，非 null
         * @throws IllegalArgumentException 端点、模型名或 API Key 缺失时
         */
        public HttpEmbeddingClient build() {
            String key = firstNonBlank(apiKey,
                    System.getProperty("clara.embedding.apiKey"),
                    System.getenv("CLARA_EMBEDDING_API_KEY"));
            String model = firstNonBlank(modelId,
                    System.getProperty("clara.embedding.model"),
                    System.getenv("CLARA_EMBEDDING_MODEL"));
            URI resolvedEndpoint = endpoint;
            if (resolvedEndpoint == null) {
                String base = firstNonBlank(baseUrl,
                        System.getProperty("clara.embedding.baseUrl"),
                        System.getenv("CLARA_EMBEDDING_BASE_URL"));
                if (base == null) {
                    throw new IllegalArgumentException("embedding endpoint must be provided via endpoint()/baseUrl() "
                            + "or clara.embedding.baseUrl / CLARA_EMBEDDING_BASE_URL");
                }
                resolvedEndpoint = toEmbeddingsEndpoint(base);
            }
            return new HttpEmbeddingClient(resolvedEndpoint, model, key, connectTimeoutMillis, readTimeoutMillis);
        }

        /**
         * 返回首个非空白字符串。
         *
         * @param values 候选值，可为 null
         * @return 首个非空白字符串；均为空白或 null 时返回 null
         */
        private static String firstNonBlank(String... values) {
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.trim().isEmpty()) {
                        return value;
                    }
                }
            }
            return null;
        }

        /**
         * 把平台基础地址规整为嵌入接口端点。
         *
         * @param baseUrl 平台基础地址
         * @return {@code /embeddings} 端点
         */
        private static URI toEmbeddingsEndpoint(String baseUrl) {
            String value = baseUrl.trim();
            if (value.endsWith("/embeddings")) {
                return URI.create(value);
            }
            if (value.endsWith("/")) {
                return URI.create(value + "embeddings");
            }
            return URI.create(value + "/embeddings");
        }
    }

    /**
     * 把单段文本向量化。
     *
     * @param text 待向量化文本，不可为 null
     * @return 向量，非 null 且长度大于 0
     * @throws IllegalArgumentException text 为 null 时
     * @throws IllegalStateException    网络失败、非 2xx 或响应结构不符时
     */
    @Override
    public float[] embed(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        ObjectNode body = json.createObjectNode();
        body.put("model", modelId);
        body.put("input", text);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + apiKey);
        RawHttpRequest request = new RawHttpRequest("POST", endpoint, headers, payload);
        IHttpCall call = httpTransport.newCall(request, transportPolicy, IExecutionGuard.NONE);
        RawHttpResponse response;
        try {
            response = call.execute();
        } catch (HttpTransportException exception) {
            throw new IllegalStateException("embedding request failed", exception);
        } finally {
            call.close();
        }
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            throw new IllegalStateException("embedding request failed: HTTP "
                    + response.getStatusCode() + " "
                    + new String(response.getBody(), StandardCharsets.UTF_8));
        }
        try {
            return parse(response.getBody());
        } catch (IOException exception) {
            throw new IllegalStateException("embedding request failed", exception);
        }
    }

    /**
     * 从响应体解析 {@code data[0].embedding} 为浮点向量。
     *
     * @param responseBody 响应体字节，非 null
     * @return 浮点向量，长度大于 0
     * @throws IOException            响应体不是合法 JSON 时
     * @throws IllegalStateException  响应缺少 data / embedding 或向量为空 / 含非数字时
     */
    private float[] parse(byte[] responseBody) throws IOException {
        JsonNode root = json.readTree(responseBody);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("embedding response missing data array");
        }
        JsonNode embedding = data.get(0).path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new IllegalStateException("embedding response missing embedding vector");
        }
        List<Float> values = new ArrayList<>();
        for (JsonNode node : embedding) {
            if (!node.isNumber()) {
                throw new IllegalStateException("embedding vector contains a non-number");
            }
            values.add(node.floatValue());
        }
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

}
