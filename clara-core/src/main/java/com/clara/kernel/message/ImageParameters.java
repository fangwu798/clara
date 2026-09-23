package com.clara.kernel.message;

import com.clara.annoation.ProviderAdapter;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * 图片生成的参数：在 {@link BasicParameters} 的通用参数之上，补充火山方舟图片生成接口
 * （{@code POST /api/v3/images/generations}，Seedream 系列）的专有字段。
 *
 *
 * <p>字段归属：{@code prompt}（提示词）与 {@code image}（参考图）在协议上属于请求内容而非模型参数，放在这里
 * 是为了让生图请求体能由本对象一次组装；{@code response_format}（图片返回形式）与 {@code stream}（是否流式）
 * 与对话共用，因此声明在父类上并按 {@code capability} 区分取值。
 *
 * <p>默认值、取值范围与模型支持情况以火山方舟图片生成接口文档为准
 * （{@code https://www.volcengine.com/docs/82379/1541523}）：同一字段在不同模型（4.0 / 4.5 / 5.0 lite /
 * 5.0 pro）上的支持情况不同，已在各字段的注解 {@code note} 中逐项标注，下发前需按注册模型过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
public abstract class ImageParameters extends BasicParameters {

    /**
     * 提示词：图片生成场景<b>必选</b>，用于描述要生成的图像内容；图层拆分场景可选，用于指定拆分意图
     * （不传则由模型自动识别图片中的主要元素并拆分为独立图层）。
     *
     * <p>语言：所有模型支持中英文提示词，seedream-5.0 pro 额外支持俄语、阿拉伯语、日语等十余种语言。
     * 字数建议：中文不超过 300 字、英文不超过 600 词，过长会分散信息、导致画面缺失元素。
     */
    @JsonProperty("prompt")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, note = "图片生成场景必选；图层拆分场景可选；中文 ≤300 字 / 英文 ≤600 词")
    @ProviderAdapter(type = ModelProviderEnum.OPENAI, capability = CapabilityKindEnum.IMAGE, note = "必选；建议 ≤32000 字符")
    private String prompt;

    /**
     * 参考图，决定生成模式：不传为文生图，传单张为单图生图，传多张为多图融合。
     * 每项可以是可公网访问的图片 URL，或 {@code data:image/<图片格式>;base64,<编码>} 形式的 Base64
     * （{@code <图片格式>} 需小写，如 {@code png}）。
     *
     * <p>张数上限随模型不同：4.5 / 4.0 / 5.0 lite 最多 14 张，5.0 pro 最多 10 张；
     * 且需满足「参考图张数 + 生成张数 ≤ 15」。
     */
    @JsonProperty("image")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, maxCount = 14, note = "不传=文生图、单张=单图生图、多张=多图融合；4.5/4.0/5.0 lite 最多 14 张，5.0 pro 最多 10 张；参考图张数 + 生成张数 ≤ 15")
    private List<String> image;

    /**
     * 生成图片的尺寸，两种写法二选一、不可混用：分辨率档位（{@code 1K}/{@code 2K}/{@code 4K}，
     * 由模型结合 prompt 中的描述决定具体宽高），或直接给出宽高像素值（{@code 宽x高}）。
     *
     * <p>像素写法需<b>同时</b>满足总像素与宽高比两个区间：总像素 [921600, 16777216]、宽高比 [1/16, 16]；
     * 档位可用值与默认值随模型不同（4.5/4.0 默认 2048x2048，5.0 lite 支持 2K/4K 且总像素下限抬高到 3686400）。
     */
    @JsonProperty("size")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, defaultValue = "2048x2048", note = "档位或宽x高二选一；总像素 [921600, 16777216]、宽高比 [1/16, 16] 需同时满足")
    @ProviderAdapter(type = ModelProviderEnum.OPENAI, capability = CapabilityKindEnum.IMAGE, allowed = {"1024x1024", "1536x1024", "1024x1536", "auto"}, note = "gpt-image-1 系列支持 auto；dall-e-3 为 1024x1024/1792x1024/1024x1792")
    private String size;

    /**
     * 随机数种子，用于控制生成内容的随机性，取值 [-1, 2147483647]，{@code -1} 表示随机。
     *
     * <p>相同请求下同 seed 会生成类似结果，但不保证完全一致；换 seed 则结果不同。
     * 火山方舟图片生成接口提供该字段（4.x 文档标注仅 3.0 系列支持，5.0 接口文档已列出，需按模型确认），
     * 其对话接口没有该字段。
     */
    @JsonProperty("seed")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, defaultValue = "-1", min = -1d, max = 2147483647d)
    private Integer seed;

    /**
     * 是否在生成的图片上添加水印（右下角「AI 生成」字样）。
     *
     * <p>平台默认<b>加水印</b>，需要无水印必须显式下发 {@code false}。
     */
    @JsonProperty("watermark")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, defaultValue = "true", note = "默认加水印；要无水印必须显式下发 false")
    private Boolean watermark;

    /**
     * 文本权重（图片与提示词的一致程度）：值越大模型自由度越小、与提示词相关性越强，取值 [1, 10]。
     *
     * <p>仅 seedream-3.0-t2i（默认 2.5）与 seededit-3.0-i2i（默认 5.5）支持，4.x 及之后的模型不支持该字段。
     */
    @JsonProperty("guidance_scale")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, min = 1d, max = 10d, note = "3.0 系列支持（默认 2.5 / 5.5）；4.x 不支持")
    private Double guidanceScale;

    /**
     * 组图开关：{@code auto} 由模型根据提示词自主判断是否返回组图及张数，{@code disabled} 只生成一张。
     *
     * <p>仅 seedream-4.5 / 4.0 支持，5.0 pro 不支持组图。
     *
     * <p>刻意不设 {@code defaultValue}：平台默认即为 {@code disabled}，而 5.0 pro 不支持该字段，
     * 下发会带来无谓的兼容风险，因此不设置时直接不下发。
     */
    @JsonProperty("sequential_image_generation")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, allowed = {"auto", "disabled"}, note = "仅 4.5/4.0 支持；5.0 pro 不支持组图；平台默认 disabled，不设置则不下发")
    private String sequentialImageGeneration;

    /**
     * 组图最多生成的图片数量，取值 [1, 15]，平台默认 15。
     *
     * <p>线格式为嵌套路径 {@code sequential_image_generation_options.max_images}，用点号在
     * {@link JsonProperty} 里直接声明层级，编码器会组装成嵌套对象；仅当
     * {@link #sequentialImageGeneration} 为 {@code auto} 时生效，因此不设默认值，不设置时不下发。
     * 实际张数还可能受参考图数量限制：输入参考图张数 + 生成张数 ≤ 15。
     */
    @JsonProperty("sequential_image_generation_options.max_images")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, min = 1d, max = 15d, note = "线格式为 sequential_image_generation_options.max_images；仅 sequentialImageGeneration=auto 时生效；平台默认 15；参考图张数 + 生成张数 ≤ 15")
    private Integer maxImages;

    /**
     * 输出图片格式。
     *
     * <p>仅 seedream-5.0 pro / 5.0 lite 支持该字段。
     */
    @JsonProperty("output_format")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, defaultValue = "jpeg", allowed = {"jpeg", "png"}, note = "仅 5.0 pro / 5.0 lite 支持")
    @ProviderAdapter(type = ModelProviderEnum.OPENAI, capability = CapabilityKindEnum.IMAGE, defaultValue = "png", allowed = {"png", "jpeg", "webp"})
    private String outputFormat;

    /**
     * 提示词优化模式：{@code standard} 质量更高但耗时更长，{@code fast} 耗时更短但质量一般。
     *
     * <p>线格式为嵌套路径 {@code optimize_prompt_options.mode}，用点号在 {@link JsonProperty} 里声明层级；
     * 仅 seedream-4.5（当前仅支持 {@code standard}）与 4.0 支持，因此不设默认值，不设置时不下发。
     */
    @JsonProperty("optimize_prompt_options.mode")
    @ProviderAdapter(type = ModelProviderEnum.VOLCENGINE, capability = CapabilityKindEnum.IMAGE, allowed = {"standard", "fast"}, note = "线格式为 optimize_prompt_options.mode；仅 4.5（standard）/4.0 支持；平台默认 standard，不设置则不下发")
    private String optimizePromptMode;

    /**
     * 生成图片的数量（OpenAI Images API），取值 [1, 10]。
     *
     * <p>gpt-image-1 / gpt-image-1-mini 支持一次生成多张；dall-e-3 仅支持 1 张。
     */
    @JsonProperty("n")
    @ProviderAdapter(type = ModelProviderEnum.OPENAI, capability = CapabilityKindEnum.IMAGE, min = 1d, max = 10d, note = "gpt-image-1 系列支持 1-10；dall-e-3 仅 1")
    private Integer n;

    /**
     * 渲染质量（OpenAI Images API）：{@code low} / {@code medium} / {@code high} / {@code auto}。
     *
     * <p>默认 {@code auto}（由模型自动选择）；gpt-image-1 系列支持，dall-e-3 用 standard/hd。
     */
    @JsonProperty("quality")
    @ProviderAdapter(type = ModelProviderEnum.OPENAI, capability = CapabilityKindEnum.IMAGE, defaultValue = "auto", allowed = {"low", "medium", "high", "auto"}, note = "gpt-image-1 系列；dall-e-3 用 standard/hd")
    private String quality;

    /**
     * 背景处理（OpenAI Images API）：{@code transparent}（透明）/ {@code opaque}（不透明）/ {@code auto}。
     *
     * <p>仅 gpt-image-1 支持透明背景。
     */
    @JsonProperty("background")
    @ProviderAdapter(type = ModelProviderEnum.OPENAI, capability = CapabilityKindEnum.IMAGE, defaultValue = "auto", allowed = {"transparent", "opaque", "auto"}, note = "仅 gpt-image-1 支持 transparent")
    private String background;

    /**
     * 内容审核强度（OpenAI Images API）：{@code low} / {@code medium} / {@code high} / {@code auto}。
     */
    @JsonProperty("moderation")
    @ProviderAdapter(type = ModelProviderEnum.OPENAI, capability = CapabilityKindEnum.IMAGE, defaultValue = "auto", allowed = {"low", "medium", "high", "auto"})
    private String moderation;

    /**
     * 输出压缩级别（OpenAI Images API），取值 [0, 100]，仅对 jpeg / webp 生效。
     */
    @JsonProperty("output_compression")
    @ProviderAdapter(type = ModelProviderEnum.OPENAI, capability = CapabilityKindEnum.IMAGE, min = 0d, max = 100d, note = "仅 jpeg/webp 生效")
    private Integer outputCompression;

    /**
     * 生成风格（OpenAI Images API，dall-e-3）：{@code vivid}（生动）/ {@code natural}（自然）。
     */
    @JsonProperty("style")
    @ProviderAdapter(type = ModelProviderEnum.OPENAI, capability = CapabilityKindEnum.IMAGE, allowed = {"vivid", "natural"}, note = "仅 dall-e-3 支持")
    private String style;
}
