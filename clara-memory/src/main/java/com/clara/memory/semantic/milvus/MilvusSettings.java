package com.clara.memory.semantic.milvus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Milvus 语义记忆配置：读取 {@code config/clara.yml} 的 {@code milvus} 节点。
 *
 * <p>是什么、在哪一层：clara-memory 的 Milvus 配置模型（{@code com.clara.memory.semantic.milvus}），
 * 与 {@link MilvusBackendConfig} / {@link MilvusCollectionSpec} 同包；宿主在装配期从工作目录的
 * {@code config/clara.yml} 读取它，据此构造 Milvus 语义记忆存储。
 *
 * <p>开关语义（「配了就开、不配保持原样」）：文件里没有 {@code milvus} 节点，或显式写了
 * {@code enabled: false}，都视为<b>未启用</b>，此时语义记忆维持进程内实现、不把对话记录写入向量库；
 * 只要存在该节点且未显式关闭，即视为启用——所有对话记录写入 Milvus，并在每轮按相似度召回上下文。
 *
 * <p>检索模式（{@code mode}）：{@code vector} 用嵌入向量做相似度检索（默认）；
 * {@code bm25} 用 Milvus 内置 BM25 全文检索（中文按 {@code analyzer} 分词，默认 chinese）。
 * BM25 模式不需要任何嵌入服务，集合里也不存向量；两种模式的集合结构不同，
 * 切换模式必须重建集合（可用 {@code recreateOnSchemaMismatch} 让启动期自动重建）。
 *
 * <p>字段缺省：{@code host} 缺省 {@code 127.0.0.1}、{@code port} 缺省 {@code 19530}、
 * {@code collection} 缺省为「项目名_conversations」（项目名由宿主按当前目录名传入，不同目录使用不同集合，
 * 避免多个项目共用同一集合互相串数据）、{@code dimension} 缺省取嵌入客户端维度（仅 vector 模式使用）、
 * {@code username} / {@code password} 缺省无鉴权、{@code analyzer} 缺省 {@code chinese}、
 * {@code recreateOnSchemaMismatch} 缺省 {@code false}。
 *
 * <p>失败语义：文件读取失败、{@code port} / {@code dimension} 非正、{@code collection} 为空白时抛
 * {@link IllegalArgumentException}（配置错误应在启动期暴露，而不是等第一次写入才失败）。
 */
public final class MilvusSettings {
    /** 配置错误消息的统一前缀。 */
    private static final String CONFIG_PREFIX = "clara.yml 的 milvus.";

    /**
     * 默认主机。
     */
    public static final String DEFAULT_HOST = "127.0.0.1"; // NOPMD AvoidUsingHardCodedIP: 本地默认地址，可由配置覆盖

    /**
     * 默认 gRPC 端口（与 deploy/docker-compose.yml 暴露的端口一致）。
     */
    public static final int DEFAULT_PORT = 19530;

    /**
     * 默认集合名的后缀：未显式配置 {@code collection} 时，集合名取「项目名 + 该后缀」。
     */
    public static final String COLLECTION_SUFFIX = "_conversations";

    /**
     * 项目名缺失时的兜底名称。
     */
    public static final String FALLBACK_PROJECT = "clara";

    /**
     * 检索模式：向量相似度检索（默认）。
     */
    public static final String MODE_VECTOR = "vector";

    /**
     * 检索模式：Milvus 内置 BM25 全文检索（中文分词后按词频 / 逆文档频率打分）。
     */
    public static final String MODE_BM25 = "bm25";

    /**
     * BM25 模式的默认分词器：Milvus 内置的 chinese（jieba），适合中文对话记录。
     */
    public static final String DEFAULT_ANALYZER = "chinese";

    /**
     * 项目名参与集合名的最大长度（Milvus 集合名上限 255，这里留出后缀空间）。
     */
    private static final int MAX_PROJECT_CHARS = 200;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final String host;
    private final int port;
    private final String collection;
    private final int dimension;
    private final String username;
    private final String password;
    private final String mode;
    private final String analyzer;
    private final boolean recreateOnSchemaMismatch;

    private MilvusSettings(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.collection = builder.collection;
        this.dimension = builder.dimension;
        this.username = builder.username;
        this.password = builder.password;
        this.mode = builder.mode;
        this.analyzer = builder.analyzer;
        this.recreateOnSchemaMismatch = builder.recreateOnSchemaMismatch;
    }

    /**
     * 创建 Milvus 配置构建器。
     *
     * @param host        Milvus 主机
     * @param port        gRPC 端口
     * @param collection  集合名
     * @param dimension   向量维度
     * @return 构建器，非 null
     */
    private static Builder builder(String host, int port, String collection, int dimension) {
        return new Builder(host, port, collection, dimension);
    }

    /**
     * 读取 Milvus 配置。
     *
     * @param claraConfig      {@code config/clara.yml} 路径，可为 null（视为未配置）
     * @param defaultDimension 未在配置里声明 {@code dimension} 时采用的向量维度（通常取嵌入客户端维度），必须为正
     * @param projectName      项目名（由宿主取「工作目录的文件名」，中文目录名已转写为英文）：
     *                         未声明 {@code collection} 时，集合名默认为「项目名_conversations」，据此按项目隔离存储；可为 null / 空白
     * @return Milvus 配置；未配置或已关闭时返回 null
     * @throws IllegalArgumentException 配置存在但非法时
     */
    public static MilvusSettings load(Path claraConfig, int defaultDimension, String projectName) {
        if (claraConfig == null || !Files.isRegularFile(claraConfig)) {
            return null;
        }
        JsonNode root;
        try {
            root = YAML.readTree(Files.readAllBytes(claraConfig));
        } catch (IOException failure) {
            throw new IllegalArgumentException("读取 " + claraConfig + " 失败：" + failure.getMessage(), failure);
        }
        JsonNode milvus = root == null ? null : root.get("milvus");
        if (milvus == null || !milvus.isObject()) {
            return null;
        }
        JsonNode enabled = milvus.get("enabled");
        if (enabled != null && enabled.isBoolean() && !enabled.booleanValue()) {
            return null;
        }
        String collection = text(milvus, "collection", defaultCollection(projectName));
        if (collection.trim().isEmpty()) {
            throw new IllegalArgumentException("clara.yml 的 milvus.collection 不能为空白");
        }
        String mode = text(milvus, "mode", MODE_VECTOR).toLowerCase(Locale.ROOT);
        if (!MODE_VECTOR.equals(mode) && !MODE_BM25.equals(mode)) {
            throw new IllegalArgumentException("clara.yml 的 milvus.mode 只能是 " + MODE_VECTOR
                    + " 或 " + MODE_BM25 + "，实际：" + mode);
        }
        return builder(text(milvus, "host", DEFAULT_HOST),
                positive(milvus, "port", DEFAULT_PORT),
                collection.trim(),
                positive(milvus, "dimension", defaultDimension))
                .username(text(milvus, "username", null))
                .password(text(milvus, "password", null))
                .mode(mode)
                .analyzer(text(milvus, "analyzer", DEFAULT_ANALYZER))
                .recreateOnSchemaMismatch(bool(milvus, "recreateOnSchemaMismatch", false))
                .build();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getCollection() {
        return collection;
    }

    public int getDimension() {
        return dimension;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    /**
     * 取检索模式：{@link #MODE_VECTOR} 或 {@link #MODE_BM25}。
     *
     * @return 检索模式，非 null
     */
    public String getMode() {
        return mode;
    }

    /**
     * 是否为 BM25 全文检索模式。
     *
     * @return 模式为 {@code bm25} 时返回 true
     */
    public boolean isBm25Mode() {
        return MODE_BM25.equals(mode);
    }

    /**
     * 取 BM25 模式的分词器名（仅 BM25 模式使用）。
     *
     * @return 分词器名，非 null
     */
    public String getAnalyzer() {
        return analyzer;
    }

    /**
     * 集合结构与配置不匹配时是否自动删除旧集合并按新结构重建。
     *
     * @return 需要自动重建时返回 true
     */
    public boolean isRecreateOnSchemaMismatch() {
        return recreateOnSchemaMismatch;
    }

    /**
     * 由项目名推导默认集合名：{@code 项目名_conversations}。项目名会先规范化为 Milvus 合法集合名
     * （仅字母 / 数字 / 下划线，且以字母或下划线开头，其余字符折为下划线），缺失时用
     * {@link #FALLBACK_PROJECT}，长度超限时截断。
     *
     * @param projectName 项目名（通常为当前目录名），可为 null / 空白
     * @return 默认集合名，非 null
     */
    public static String defaultCollection(String projectName) {
        return normalizeProject(projectName) + COLLECTION_SUFFIX;
    }

    private static String normalizeProject(String raw) {
        String text = trimUnderscores(sanitizeProject(raw == null ? "" : raw.trim()));
        if (text.isEmpty()) {
            text = FALLBACK_PROJECT;
        }
        if (text.charAt(0) >= '0' && text.charAt(0) <= '9') {
            text = "p" + text;
        }
        return text.length() > MAX_PROJECT_CHARS ? text.substring(0, MAX_PROJECT_CHARS) : text;
    }

    private static String sanitizeProject(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '_';
            builder.append(allowed ? Character.toLowerCase(c) : '_');
        }
        return builder.toString();
    }

    private static String trimUnderscores(String text) {
        String value = text;
        while (value.contains("__")) {
            value = value.replace("__", "_");
        }
        while (value.startsWith("_")) {
            value = value.substring(1);
        }
        while (value.endsWith("_")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 读取文本字段：缺失、为 null 或为空串时返回兜底值。
     */
    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }

    /**
     * 读取布尔字段：缺失时返回兜底值，声明了非布尔时抛配置错误。
     *
     * @param node     集合节点，非 null
     * @param field    字段名
     * @param fallback 缺失时的兜底值
     * @return 字段值
     * @throws IllegalArgumentException 字段存在但不是布尔时
     */
    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(CONFIG_PREFIX + field + " 必须是布尔值，实际：" + value);
        }
        return value.booleanValue();
    }

    /**
     * 读取正整数字段：缺失时返回兜底值，声明了非正或非数字时抛配置错误。
     */
    private static int positive(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (fallback <= 0) {
                throw new IllegalArgumentException(CONFIG_PREFIX + field + " 必须为正整数");
            }
            return fallback;
        }
        if (!value.canConvertToInt() || value.asInt() <= 0) {
            throw new IllegalArgumentException(CONFIG_PREFIX + field + " 必须为正整数，实际：" + value);
        }
        return value.asInt();
    }

    /**
     * Milvus 配置构建器；核心连接参数在工厂方法中传入，其余字段按需设置。
     */
    private static final class Builder {

        private final String host;
        private final int port;
        private final String collection;
        private final int dimension;
        private String username;
        private String password;
        private String mode = MODE_VECTOR;
        private String analyzer = DEFAULT_ANALYZER;
        private boolean recreateOnSchemaMismatch;

        private Builder(String host, int port, String collection, int dimension) {
            this.host = host;
            this.port = port;
            this.collection = collection;
            this.dimension = dimension;
        }

        private Builder username(String username) {
            this.username = username;
            return this;
        }

        private Builder password(String password) {
            this.password = password;
            return this;
        }

        private Builder mode(String mode) {
            this.mode = mode;
            return this;
        }

        private Builder analyzer(String analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        private Builder recreateOnSchemaMismatch(boolean recreateOnSchemaMismatch) {
            this.recreateOnSchemaMismatch = recreateOnSchemaMismatch;
            return this;
        }

        private MilvusSettings build() {
            return new MilvusSettings(this);
        }
    }
}
