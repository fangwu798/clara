package com.clara.kernel.message.provider;

import com.clara.core.model.IRequest;
import com.clara.core.transport.RawHttpRequest;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.message.ImageParameters;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI 图片编辑请求（multipart {@code /v1/images/edits}）的构造器。
 *
 * <p>OpenAI 图片编辑与文生图走不同端点、不同媒体类型：有参考图时必须把 {@code prompt} 等字段与图片
 * 一起编码成 multipart，并把端点从 {@code /images/generations} 换成 {@code /images/edits}。本类只在
 * 「平台 OPENAI + 能力 IMAGE + 参考图非空」时被 {@link AbstractRequestEncoder} 调用，其余情况保持原
 * 有的 JSON 编码。
 */
final class OpenAiImageEditRequest {

    private static final String GENERATIONS_SUFFIX = "/images/generations";
    private static final String EDITS_SUFFIX = "/images/edits";
    private static final String IMAGE_FIELD = "image";
    private static final String MULTIPART_TYPE = "multipart/form-data; boundary=";
    private static final String LINE_BREAK = "\r\n";

    private OpenAiImageEditRequest() {
    }

    /**
     * 判断当前请求是否需要走 multipart 图片编辑。
     *
     * @param provider   平台，非 null
     * @param capability 能力种类，非 null
     * @param request    请求对象，非 null
     * @return 平台为 OPENAI、能力为 IMAGE 且至少有一张参考图时返回 true
     */
    static boolean supports(ModelProviderEnum provider, CapabilityKindEnum capability, IRequest request) {
        if (provider != ModelProviderEnum.OPENAI || capability != CapabilityKindEnum.IMAGE
                || !(request instanceof ImageParameters)) {
            return false;
        }
        List<String> images = ((ImageParameters) request).getImage();
        return images != null && !images.isEmpty();
    }

    /**
     * 构造 multipart 图片编辑请求。
     *
     * @param config  能力配置，提供文生图端点与模型标识，非 null
     * @param headers 已合并授权头的请求头，非 null
     * @param body    已编码的请求体字段，非 null
     * @param images  参考图引用，非空
     * @return 可直接交给传输实现的请求，非 null
     */
    static RawHttpRequest build(ModelConfig config, Map<String, String> headers,
            Map<String, Object> body, List<String> images) {
        String boundary = "----ClaraBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        writeFields(buffer, boundary, body);
        writeImages(buffer, boundary, images);
        write(buffer, "--" + boundary + "--" + LINE_BREAK);
        return new RawHttpRequest("POST", editEndpoint(config.getEndpoint()),
                multipartHeaders(headers, boundary), buffer.toByteArray());
    }

    private static void writeFields(ByteArrayOutputStream buffer, String boundary, Map<String, Object> body) {
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (IMAGE_FIELD.equals(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            writeText(buffer, boundary, entry.getKey(), String.valueOf(entry.getValue()));
        }
    }

    private static void writeText(ByteArrayOutputStream buffer, String boundary, String name, String value) {
        write(buffer, "--" + boundary + LINE_BREAK);
        write(buffer, "Content-Disposition: form-data; name=\"" + name + "\"" + LINE_BREAK);
        write(buffer, LINE_BREAK);
        write(buffer, value);
        write(buffer, LINE_BREAK);
    }

    private static void writeImages(ByteArrayOutputStream buffer, String boundary, List<String> images) {
        int usable = 0;
        for (String image : images) {
            if (image != null && !image.trim().isEmpty()) {
                usable++;
            }
        }
        String field = usable > 1 ? IMAGE_FIELD + "[]" : IMAGE_FIELD;
        int index = 0;
        for (String image : images) {
            if (image == null || image.trim().isEmpty()) {
                continue;
            }
            index++;
            writeFile(buffer, boundary, field, ImageSource.read(image, index));
        }
    }

    private static void writeFile(ByteArrayOutputStream buffer, String boundary, String name, ImageSource source) {
        byte[] bytes = source.getBytes();
        write(buffer, "--" + boundary + LINE_BREAK);
        write(buffer, "Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                + escape(source.getFileName()) + "\"" + LINE_BREAK);
        write(buffer, "Content-Type: " + source.getContentType() + LINE_BREAK);
        write(buffer, LINE_BREAK);
        buffer.write(bytes, 0, bytes.length);
        write(buffer, LINE_BREAK);
    }

    private static Map<String, String> multipartHeaders(Map<String, String> headers, String boundary) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!"content-type".equals(header.getKey().toLowerCase(Locale.ROOT))) {
                merged.put(header.getKey(), header.getValue());
            }
        }
        merged.put("Content-Type", MULTIPART_TYPE + boundary);
        return merged;
    }

    private static URI editEndpoint(URI endpoint) {
        String value = endpoint.toString();
        int index = value.indexOf(GENERATIONS_SUFFIX);
        if (index < 0) {
            throw new IllegalStateException("OPENAI 图片编辑要求 IMAGE 端点包含 "
                    + GENERATIONS_SUFFIX + "：" + value);
        }
        return URI.create(value.substring(0, index) + EDITS_SUFFIX
                + value.substring(index + GENERATIONS_SUFFIX.length()));
    }

    private static String escape(String value) {
        return value.replace("\\", "_").replace("\"", "_");
    }

    private static void write(ByteArrayOutputStream buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buffer.write(bytes, 0, bytes.length);
    }
}
