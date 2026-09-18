package com.clara.model.provider.compatible.image;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.model.provider.media.MediaJson;
import com.clara.model.utils.ExceptionUtils;
import com.clara.model.utils.JsonNodeUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 累积 OpenAI 兼容流式生图的事件，并把每个事件转成「可推送的增量」。
 *
 * <p>用途：兼容家族的流式生图把结果拆成若干事件放在 SSE 的 {@code data} 里，用 {@code type} 字段区分：
 * <ul>
 *   <li>{@code image_generation.partial_succeeded}：单张图片已生成，带 {@code image_index} 与
 *       {@code url}（或 {@code b64_json}）；</li>
 *   <li>{@code image_generation.partial_failed}：单张图片失败，带 {@code image_index} 与
 *       {@code error{code,message}}；</li>
 *   <li>{@code image_generation.completed}：整条流收尾，带 {@code usage} 汇总；</li>
 *   <li>顶层 {@code error}：整条流失败（参数非法、鉴权失败等），此时平台不会再发收尾事件。</li>
 * </ul>
 * 本类把这些结构解析成 {@link Chunk}，让模型类只负责落盘与推送事件，不必接触平台的事件结构。
 *
 * <p>使用方式：一个实例只服务一次流，必须在读取线程上串行调用 {@link #accept(String)}；返回块的
 * {@link Chunk#getKind()} 为 {@link Kind#COMPLETE} 表示流已收尾，之后再次传入数据按协议错误拒绝。
 * 请求标识由 {@link #getRequestId()} 提供，可能为 null（平台未下发时）。
 *
 * <p>错误处理：顶层错误直接抛 {@link ClaraException}（整条流无法继续）；单张图片失败不抛异常，而是作为
 * {@link Kind#IMAGE_FAILED} 增量返回，让调用方决定是继续等待其余图片还是中断——这正是流式生图相对
 * 非流式的价值所在。
 */
final class OpenAiCompatibleImageStreamAccumulator {
    /**
     * 事件类型：单张图片生成成功。
     */
    private static final String PARTIAL_SUCCEEDED = "image_generation.partial_succeeded";
    /**
     * 事件类型：单张图片生成失败。
     */
    private static final String PARTIAL_FAILED = "image_generation.partial_failed";
    /**
     * 事件类型：整条流收尾。
     */
    private static final String COMPLETED = "image_generation.completed";

    /**
     * 图片配置，提供平台标识用于错误归因。
     */
    private final OpenAiCompatibleImageConfig config;
    /**
     * 与平台绑定的 JSON 读取工具，用于解析每个事件。
     */
    private final MediaJson json;
    /**
     * 平台下发的请求标识，取首个非空值，未下发时为 null。
     */
    private String requestId;
    /**
     * 是否已收到收尾事件。
     */
    private boolean complete;

    /**
     * 创建累加器。
     *
     * @param config 图片配置，不可为 null
     * @throws IllegalArgumentException config 为 null 时
     */
    OpenAiCompatibleImageStreamAccumulator(OpenAiCompatibleImageConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.json = new MediaJson(config.getProvider());
    }

    /**
     * 消费一个 SSE 事件的数据部分，返回本次可推送的增量。
     *
     * @param data 一个事件的 {@code data} 字段内容（JSON 文本）
     * @return 本次增量块
     * @throws IllegalArgumentException data 为 null 时
     * @throws ClaraException           流已收尾后继续传入数据、事件结构非法、未知事件类型，或平台自报整条流失败时
     */
    Chunk accept(String data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (complete) {
            throw ExceptionUtils.protocolError(config.getProvider());
        }
        try {
            JsonNode root = json.read(data.getBytes(StandardCharsets.UTF_8));
            if (!root.isObject()) {
                throw ExceptionUtils.protocolError(config.getProvider());
            }
            // 顶层 error 表示整条流失败，平台不会再发收尾事件，直接抛出由上层转为 ERROR 终态事件
            if (root.hasNonNull("error")) {
                throw ExceptionUtils.providerEventFailure(config.getProvider(), errorCode(root.get("error")));
            }
            String candidate = readRequestId(root);
            if (candidate != null) {
                requestId = candidate;
            }
            String type = JsonNodeUtils.strictTextOrNull(root, "type");
            if (PARTIAL_SUCCEEDED.equals(type)) {
                return Chunk.image(imageIndex(root), OpenAiCompatibleImageCodec.output(root, config));
            }
            if (PARTIAL_FAILED.equals(type)) {
                return Chunk.failed(imageIndex(root), failure(root));
            }
            if (COMPLETED.equals(type)) {
                complete = true;
                return Chunk.complete(usage(root.get("usage")));
            }
            throw ExceptionUtils.protocolError(config.getProvider());
        } catch (ClaraException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ExceptionUtils.protocolError(config.getProvider());
        }
    }

    /**
     * 查询流是否已收尾。
     *
     * @return 已收到 {@code image_generation.completed} 时返回 true
     */
    boolean isComplete() {
        return complete;
    }

    /**
     * 读取平台下发的请求标识。
     *
     * <p>与非流式响应一致：火山方舟用 {@code request_id}，其余兼容平台用 {@code id}。
     *
     * @return 请求标识，平台未下发时为 null
     */
    String getRequestId() {
        return requestId;
    }

    /**
     * 解析单张图片失败的原因。
     *
     * @param root 事件根节点
     * @return 领域错误，只保留平台错误码，不携带平台返回的文本
     * @throws ClaraException 缺少 {@code error} 对象时
     */
    private ClaraError failure(JsonNode root) {
        JsonNode error = root.get("error");
        if (error == null || !error.isObject()) {
            throw ExceptionUtils.protocolError(config.getProvider());
        }
        return ExceptionUtils.providerEventFailure(config.getProvider(), errorCode(error)).getError();
    }

    /**
     * 读取平台错误码。
     *
     * @param error 错误对象，可为 null
     * @return 平台错误码，缺失时为 null
     */
    private static String errorCode(JsonNode error) {
        return error != null && error.isObject() ? JsonNodeUtils.strictTextOrNull(error, "code") : null;
    }

    /**
     * 读取图片序号。
     *
     * @param root 事件根节点
     * @return 图片在组图中的位置，从 0 开始
     * @throws ClaraException 字段缺失、非整数或为负数时
     */
    private int imageIndex(JsonNode root) {
        JsonNode value = root.get("image_index");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw ExceptionUtils.protocolError(config.getProvider());
        }
        return value.intValue();
    }

    /**
     * 读取请求标识。
     *
     * @param root 事件根节点
     * @return 请求标识，缺失或为空时返回 null
     */
    private static String readRequestId(JsonNode root) {
        String value = JsonNodeUtils.strictTextOrNull(root, "request_id");
        return value == null ? JsonNodeUtils.strictTextOrNull(root, "id") : value;
    }

    /**
     * 把收尾事件的用量转换成结果元数据。
     *
     * @param usage {@code usage} 节点，可为 null
     * @return 不可变元数据，字段缺失时不写入
     */
    private static Map<String, String> usage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return Collections.emptyMap();
        }
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        put(metadata, "generatedImages", JsonNodeUtils.optionalIntValue(usage, "generated_images"));
        put(metadata, "inputImages", JsonNodeUtils.optionalIntValue(usage, "input_images"));
        put(metadata, "outputTokens", JsonNodeUtils.optionalIntValue(usage, "output_tokens"));
        put(metadata, "totalTokens", JsonNodeUtils.optionalIntValue(usage, "total_tokens"));
        JsonNode tools = usage.get("tool_usage");
        if (tools != null && tools.isObject()) {
            put(metadata, "webSearchCount", JsonNodeUtils.optionalIntValue(tools, "web_search"));
        }
        return metadata;
    }

    /**
     * 写入非空的计量字段。
     *
     * @param target 目标元数据
     * @param key    字段名
     * @param value  字段值，可为 null
     */
    private static void put(Map<String, String> target, String key, Integer value) {
        if (value != null) {
            target.put(key, value.toString());
        }
    }

    /**
     * 表示一个事件解析出的增量，三种取值互斥。
     */
    enum Kind {
        /**
         * 单张图片已生成，携带图片序号与输出内容。
         */
        IMAGE,
        /**
         * 单张图片生成失败，携带图片序号与错误。
         */
        IMAGE_FAILED,
        /**
         * 整条流已收尾，携带汇总元数据。
         */
        COMPLETE
    }

    /**
     * 承载一个事件解析出的增量。
     */
    static final class Chunk {
        /**
         * 增量类型。
         */
        private final Kind kind;
        /**
         * 图片序号，仅 IMAGE 与 IMAGE_FAILED 有效。
         */
        private final int imageIndex;
        /**
         * 单张图片的输出，仅 IMAGE 非 null。
         */
        private final OpenAiCompatibleImageCodec.Output output;
        /**
         * 单张图片的错误，仅 IMAGE_FAILED 非 null。
         */
        private final ClaraError error;
        /**
         * 收尾事件的元数据，仅 COMPLETE 非 null。
         */
        private final Map<String, String> metadata;

        private Chunk(Kind kind, int imageIndex, OpenAiCompatibleImageCodec.Output output, ClaraError error,
                Map<String, String> metadata) {
            this.kind = kind;
            this.imageIndex = imageIndex;
            this.output = output;
            this.error = error;
            this.metadata = metadata;
        }

        static Chunk image(int imageIndex, OpenAiCompatibleImageCodec.Output output) {
            return new Chunk(Kind.IMAGE, imageIndex, output, null, null);
        }

        static Chunk failed(int imageIndex, ClaraError error) {
            return new Chunk(Kind.IMAGE_FAILED, imageIndex, null, error, null);
        }

        static Chunk complete(Map<String, String> metadata) {
            return new Chunk(Kind.COMPLETE, 0, null, null, metadata);
        }

        Kind getKind() {
            return kind;
        }

        int getImageIndex() {
            return imageIndex;
        }

        OpenAiCompatibleImageCodec.Output getOutput() {
            return output;
        }

        ClaraError getError() {
            return error;
        }

        Map<String, String> getMetadata() {
            return metadata;
        }
    }
}
