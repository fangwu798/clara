package com.clara.kernel.message.delta;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 图片条目：图片生成结果里的一张图。
 *
 * <p>是什么、在哪一层：契约层 {@code com.clara.kernel.message.delta} 下的响应值对象。它是统一响应对象
 * （{@code HttpResponse}）中 {@code images} 字段的元素，对应火山方舟生图响应的 {@code data[]}；与
 * 「增量」同包是因为它承载的是模型输出结果中的一项，而非调用参数。
 *
 * <p>谁创建谁调用：由 {@code ProviderResponseDecoder} 在解码响应体时通过 Jackson 构造（因此需要无参构造器），
 * 调用方只读使用。本类不持有资源，不需要 {@code close()}。
 *
 * <p>使用方法：从响应对象的 {@code images} 取回列表后，按 {@link #getUrl()} 或 {@link #getB64Json()}
 * 取得图片本体——两者由一个生效：{@code response_format = url} 时只有 {@link #getUrl()}，
 * {@code response_format = b64_json} 时只有 {@link #getB64Json()}。
 *
 * <p>失败语义：本类不做校验；两个字段都为 null 时说明平台返回了无法使用的结果，由调用方判定。
 */
@Data
public class ImageItem {

    /**
     * 图片下载链接；{@code response_format = url} 时非空。
     *
     * <p>注意：链接是平台侧的临时地址（方舟为 24 小时内有效），需要长期保存时必须先下载转存。
     */
    private String url;

    /**
     * Base64 图片数据；{@code response_format = b64_json} 时非空。
     */
    @JsonProperty("b64_json")
    private String b64Json;

    /**
     * 该图的实际尺寸，形如 {@code 2048x2048}；平台未返回时为 null。
     */
    private String size;
}
