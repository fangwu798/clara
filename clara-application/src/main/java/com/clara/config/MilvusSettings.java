package com.clara.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Milvus 语义记忆配置：读取 {@code config/clara.yml} 的 {@code milvus} 节点。
 *
 * <p>是什么、在哪一层：application 的配置访问组件（{@code com.clara.config}），与 {@link ConfigLocator} 并列。
 *
 * <p>开关语义（「配了就开、不配保持原样」）：文件里没有 {@code milvus} 节点，或显式写了
 * {@code enabled: false}，都视为<b>未启用</b>，此时语义记忆维持进程内实现、不把对话记录写入向量库；
 * 只要存在该节点且未显式关闭，即视为启用——所有对话记录写入 Milvus，并在每轮按相似度召回上下文。
 *
 * <p>字段缺省：{@code host} 缺省 {@code 127.0.0.1}、{@code port} 缺省 {@code 19530}、
 * {@code collection} 缺省 {@code clara_conversations}、{@code dimension} 缺省取嵌入客户端维度、
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
     * 默认集合名。
     */
    public static final String DEFAULT_COLLECTION = "clara_conversations";

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
     * @return Milvus 配置；未配置或已关闭时返回 null
     * @throws IllegalArgumentException 配置存在但非法时
     */
    public static MilvusSettings load(Path claraConfig, int defaultDimension) {
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
        String collection = text(milvus, "collection", DEFAULT_COLLECTION);
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
