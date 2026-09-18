package com.clara.model.provider.compatible.image;

import com.clara.media.MediaType;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.provider.media.MediaJson;
import com.clara.model.provider.media.MultipartBody;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.utils.ExceptionUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 承载 OpenAI 兼容图片「请求编码与响应解析」的协议实现。
 *
 * <p>用途：兼容家族的图片接口共用同一套信封——请求核心字段是 {@code model}/{@code prompt}/{@code n}/
 * {@code size}，响应是 {@code data[].url}（或 {@code data[].b64_json}）加一个可选的请求标识。这些通用部分
 * 集中在本类；平台专属字段与限制由 {@link OpenAiCompatibleImageOptions} 补充，本类不认识任何具体平台。
 *
 * <p>两条编码路径：
 * <ul>
 *   <li>{@link #encodeJson}：文生图与「以 URL 传源图」的平台走这条，源图由平台选项写入；</li>
 *   <li>{@link #encodeMultipart}：需要上传源图文件的编辑接口走这条（multipart/form-data）。</li>
 * </ul>
 *
 * <p>注意：响应中的 {@code data} 数量必须与请求的 {@code n} 一致，否则视为协议错误；内联结果按 PNG 落盘，
 * 远程结果由资源层按 URL 探测类型。
 */
final class OpenAiCompatibleImageCodec {
    /**
     * 内联图片（{@code b64_json}）落盘时使用的 MIME 类型。
     */
    private static final String INLINE_MIME_TYPE = "image/png";
    /**
     * 远程结果落盘时使用的 MIME 类型掩码，真实类型由资源层探测。
     */
    private static final String REMOTE_MIME_TYPE = "image/*";
    /**
     * multipart 文件的 MIME 类型：编辑接口按二进制流接收源图。
     */
    private static final String UPLOAD_MIME_TYPE = "application/octet-stream";

    /**
     * 与平台绑定的 JSON 工具，负责写入请求体与读取响应体并把失败归因到平台。
     */
    private final MediaJson json;

    /**
     * 创建协议编解码器。
     *
     * @param config 图片配置，用于错误归因与平台标识
     */
    OpenAiCompatibleImageCodec(OpenAiCompatibleImageConfig config) {
        this.json = new MediaJson(config.getProvider());
    }

    /**
     * 编码 JSON 请求体：写入通用字段后交给平台选项补充专属字段。
     *
     * @param request 图片请求
     * @param config  图片配置
     * @param options 平台扩展点
     * @param assets  资源桥，供平台选项把资产 ID 换成平台可访问的引用
     * @param stream  是否要求平台以事件流返回结果，即请求体中的 {@code stream=true}
     * @return UTF-8 编码的请求体
     * @throws IllegalArgumentException 平台选项与配置不匹配时
     */
    byte[] encodeJson(ImageGenerationRequest request, OpenAiCompatibleImageConfig config, OpenAiCompatibleImageOptions options, ProviderMediaAssetBridge assets, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", config.getModelId());
        body.put("prompt", request.getPrompt());
        body.put("n", Integer.valueOf(request.getCount()));
        size(body, request);
        // stream 属于兼容家族通用字段，由协议层决定，不交给平台选项；写在选项之前，避免被平台字段覆盖
        if (stream) {
            body.put("stream", Boolean.TRUE);
        }
        options.applyToJson(request, body, assets);
        return json.write(body);
    }

    /**
     * 编码 multipart 编辑请求：写入通用字段与源图文件后交给平台选项补充专属文本字段。
     *
     * @param request 图片请求，必须带源图
     * @param config  图片配置，提供源图字节上限
     * @param assets  资源桥，用于读取源图字节
     * @return 未收尾的 multipart 请求体，调用方需再取 {@code contentType()} 与 {@code finish()}
     * @throws IllegalArgumentException 未配置源图字节上限时
     */
    MultipartBody encodeMultipart(ImageGenerationRequest request, OpenAiCompatibleImageConfig config, ProviderMediaAssetBridge assets) {
        if (config.getMaxInputBytes() <= 0) {
            throw new IllegalArgumentException("multipart edits require a positive maxInputBytes");
        }
        MultipartBody body = new MultipartBody(boundary(request.getContext().getTraceId()));
        body.text("model", config.getModelId()).text("prompt", request.getPrompt()).text("n", String.valueOf(request.getCount()));
        if (request.getWidth() != null) {
            body.text("size", request.getWidth() + "x" + request.getHeight());
        }
        boolean single = request.getSourceImageAssetIds().size() == 1;
        int index = 0;
        for (String assetId : request.getSourceImageAssetIds()) {
            body.file(single ? "image" : "image[]", "image-" + (++index) + ".bin", UPLOAD_MIME_TYPE,
                    assets.inputBytes(assetId, MediaType.IMAGE, config.getMaxInputBytes(), request.getContext()));
        }
        if (request.getMaskAssetId() != null) {
            body.file("mask", "mask.bin", UPLOAD_MIME_TYPE, assets.inputBytes(request.getMaskAssetId(),
                    MediaType.IMAGE, config.getMaxInputBytes(), request.getContext()));
        }
        return body;
    }

    /**
     * 解析响应体：校验 {@code data} 结构并区分远程 URL 与内联字节。
     *
     * @param responseBody  响应体字节
     * @param config        图片配置
     * @param expectedCount 请求的生成数量，响应条目数必须与之一致
     * @return 解析结果，含每条输出与平台请求标识
     * @throws com.clara.kernel.ClaraException 响应结构不符合协议或内联内容不是合法 Base64 时
     */
    Decoded decode(byte[] responseBody, OpenAiCompatibleImageConfig config, int expectedCount) {
        JsonNode root = json.read(responseBody);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.size() != expectedCount) {
            throw ExceptionUtils.protocolError(config.getProvider());
        }
        List<Output> outputs = new ArrayList<Output>();
        for (JsonNode item : data) {
            outputs.add(output(item, config));
        }
        return new Decoded(outputs, requestId(root));
    }

    /**
     * 读取平台侧的请求标识。
     *
     * <p>火山方舟返回 {@code request_id}，OpenAI 返回 {@code id}，都缺失时为空。
     *
     * @param root 响应根节点
     * @return 请求标识，未提供时为 null
     */
    private static String requestId(JsonNode root) {
        if (root.hasNonNull("request_id")) {
            return root.get("request_id").asText();
        }
        return root.hasNonNull("id") ? root.get("id").asText() : null;
    }

    /**
     * 解析一条生成结果：优先取内联字节，其次取远程地址。
     *
     * <p>非流式响应中的 {@code data[]} 与流式事件中的单张图片共用同一结构，因此两条链路共用本方法。
     *
     * @param item   结果条目或流式事件，含 {@code b64_json} 或 {@code url}
     * @param config 图片配置，用于错误归因
     * @return 一条输出
     * @throws com.clara.kernel.ClaraException Base64 非法或缺少可用地址时
     */
    static Output output(JsonNode item, OpenAiCompatibleImageConfig config) {
        if (item.hasNonNull("b64_json")) {
            try {
                return Output.inline(Base64.getDecoder().decode(item.get("b64_json").asText()));
            } catch (IllegalArgumentException failure) {
                throw ExceptionUtils.protocolError(config.getProvider());
            }
        }
        return Output.remote(MediaJson.requiredText(item, "url", config.getProvider()));
    }

    /**
     * 写入通用尺寸字段：优先使用宽高，其次使用比例。
     *
     * @param body    请求体
     * @param request 图片请求
     */
    private static void size(Map<String, Object> body, ImageGenerationRequest request) {
        if (request.getWidth() != null) {
            body.put("size", request.getWidth() + "x" + request.getHeight());
        } else if (request.getAspectRatio() != null) {
            body.put("size", request.getAspectRatio());
        }
    }

    /**
     * 由链路 ID 派生合法的 multipart 边界。
     *
     * @param traceId 链路 ID
     * @return multipart 边界
     */
    private static String boundary(String traceId) {
        String clean = traceId.replaceAll("[^A-Za-z0-9_-]", "");
        String padded = (clean + "0000000000000000").substring(0, 16);
        return "ClaraBoundary" + padded;
    }

    /**
     * 表示一次响应的解析结果。
     */
    static final class Decoded {
        /**
         * 每条生成结果，顺序与响应中的 {@code data} 一致。
         */
        private final List<Output> outputs;
        /**
         * 平台请求标识，未提供时为 null。
         */
        private final String requestId;

        Decoded(List<Output> outputs, String requestId) {
            this.outputs = outputs;
            this.requestId = requestId;
        }

        List<Output> getOutputs() {
            return outputs;
        }

        String getRequestId() {
            return requestId;
        }
    }

    /**
     * 表示一条生成结果：远程 URL 或内联图片字节，两者必有且仅有一个。
     */
    static final class Output {
        /**
         * 平台返回的图片地址，内联结果时为 null。
         */
        private final String url;
        /**
         * 平台返回的图片字节，远程结果时为 null。
         */
        private final byte[] bytes;

        private Output(String url, byte[] bytes) {
            this.url = url;
            this.bytes = bytes;
        }

        static Output remote(String url) {
            return new Output(url, null);
        }

        static Output inline(byte[] bytes) {
            return new Output(null, bytes);
        }

        boolean isInline() {
            return bytes != null;
        }

        String getUrl() {
            return url;
        }

        byte[] getBytes() {
            return bytes;
        }

        /**
         * 读取落盘时使用的 MIME 类型。
         *
         * @return 内联结果为 {@code image/png}，远程结果为 {@code image/*}
         */
        String getMimeType() {
            return isInline() ? INLINE_MIME_TYPE : REMOTE_MIME_TYPE;
        }
    }
}
