package com.clara.memory.semantic.milvus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * <p>字段缺省：{@code host} 缺省 {@code 127.0.0.1}、{@code port} 缺省 {@code 19530}、
 * {@code collection} 缺省为「项目名_conversations」（项目名由宿主按当前目录名传入，不同目录使用不同集合，
 * 避免多个项目共用同一集合互相串数据）、{@code dimension} 缺省取嵌入客户端维度、
 * {@code username} / {@code password} 缺省无鉴权。
 *
 * <p>失败语义：文件读取失败、{@code port} / {@code dimension} 非正、{@code collection} 为空白时抛
 * {@link IllegalArgumentException}（配置错误应在启动期暴露，而不是等第一次写入才失败）。
 */
public final class MilvusSettings {

    /**
     * 默认主机。
     */
    public static final String DEFAULT_HOST = "127.0.0.1";

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

    private MilvusSettings(String host, int port, String collection, int dimension, String username,
            String password) {
        this.host = host;
        this.port = port;
        this.collection = collection;
        this.dimension = dimension;
        this.username = username;
        this.password = password;
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
        return new MilvusSettings(text(milvus, "host", DEFAULT_HOST),
                positive(milvus, "port", DEFAULT_PORT),
                collection.trim(),
                positive(milvus, "dimension", defaultDimension),
                text(milvus, "username", null),
                text(milvus, "password", null));
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
        String value = raw == null ? "" : raw.trim();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            builder.append(allowed ? Character.toLowerCase(c) : '_');
        }
        String text = builder.toString();
        while (text.contains("__")) {
            text = text.replace("__", "_");
        }
        while (text.startsWith("_")) {
            text = text.substring(1);
        }
        while (text.endsWith("_")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            text = FALLBACK_PROJECT;
        }
        if (text.charAt(0) >= '0' && text.charAt(0) <= '9') {
            text = "p" + text;
        }
        return text.length() > MAX_PROJECT_CHARS ? text.substring(0, MAX_PROJECT_CHARS) : text;
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
     * 读取正整数字段：缺失时返回兜底值，声明了非正或非数字时抛配置错误。
     */
    private static int positive(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (fallback <= 0) {
                throw new IllegalArgumentException("clara.yml 的 milvus." + field + " 必须为正整数");
            }
            return fallback;
        }
        if (!value.canConvertToInt() || value.asInt() <= 0) {
            throw new IllegalArgumentException("clara.yml 的 milvus." + field + " 必须为正整数，实际：" + value);
        }
        return value.asInt();
    }
}
