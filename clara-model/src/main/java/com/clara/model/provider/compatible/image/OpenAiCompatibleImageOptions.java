package com.clara.model.provider.compatible.image;

import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.provider.media.MultipartBody;
import com.clara.model.provider.media.ProviderMediaAssetBridge;

import java.util.Map;

/**
 * 定义 OpenAI 兼容图片协议的平台扩展点。
 *
 * <p>用途：把「协议骨架」与「平台差异」分开——{@link OpenAiCompatibleImageCodec} 负责兼容家族通用的
 * 请求字段与响应信封，各平台自己的字段与限制则由本接口的实现补充，使协议包不需要认识任何具体平台。
 *
 * <p>三个钩子各司其职：
 * <ul>
 *   <li>{@link #applyToJson}：往 JSON 请求体写平台专属字段（如火山的 {@code watermark}/{@code seed}/
 *       {@code image}，OpenAI 的 {@code response_format}/{@code quality}/{@code style}）；</li>
 *   <li>{@link #applyToMultipart}：往 multipart 编辑请求写平台专属文本字段（仅走编辑上传的平台需要）；</li>
 *   <li>{@link #validate}：拒绝本平台不支持的请求字段，让错误在发送前暴露；</li>
 *   <li>{@link #usesMultipartEdit}：决定带源图的请求是走 multipart 编辑上传，还是走 JSON（源图以 URL 下发）。</li>
 * </ul>
 *
 * <p>实现必须是不可变且线程安全的：同一个实例会被复用，且可能被多个调用并发使用。
 */
public interface OpenAiCompatibleImageOptions {
    /**
     * 往 JSON 请求体中补充平台专属字段。
     *
     * <p>调用时请求体已包含通用字段（{@code model}/{@code prompt}/{@code n}/{@code size}），实现只应新增
     * 或覆盖自己负责的字段。
     *
     * @param request 图片请求，提供平台参数与调用上下文
     * @param body    已包含通用字段的请求体，实现可直接写入
     * @param assets  资源桥，用于把本地资产 ID 换成平台可访问的引用
     */
    void applyToJson(ImageGenerationRequest request, Map<String, Object> body, ProviderMediaAssetBridge assets);

    /**
     * 往 multipart 编辑请求中补充平台专属文本字段。
     *
     * <p>默认不做任何补充；只有 {@link #usesMultipartEdit} 可能返回 true 的平台才需要实现。
     *
     * @param request 图片请求
     * @param body    已包含通用字段与源图文件的 multipart 请求体
     */
    default void applyToMultipart(ImageGenerationRequest request, MultipartBody body) {
    }

    /**
     * 校验本次请求是否使用了本平台不支持的能力或字段。
     *
     * @param request 图片请求
     * @throws RuntimeException 平台不支持该请求时
     */
    default void validate(ImageGenerationRequest request) {
    }

    /**
     * 判断带源图的请求是否走 multipart 编辑上传。
     *
     * <p>默认返回 false，即源图以 JSON 字段下发（供只支持图片 URL 的平台使用）；OpenAI 的编辑接口需要
     * 上传文件本身，因此返回 {@code request.isEdit()}。
     *
     * @param request 图片请求
     * @return true 表示走 multipart 编辑上传
     */
    default boolean usesMultipartEdit(ImageGenerationRequest request) {
        return false;
    }
}
