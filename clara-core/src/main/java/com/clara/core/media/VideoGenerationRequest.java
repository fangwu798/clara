package com.clara.core.media;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 视频生成请求：提示词、参考图与过程回调。
 *
 * <p>参考图用于图生视频；没有参考图时即文生视频。过程回调用于把「任务已提交 / 正在生成」等状态
 * 及时反馈给用户，避免长耗时调用看起来像卡死。
 */
@Getter
public final class VideoGenerationRequest {

    /** 生成提示词，非空白。
     * -- GETTER --
     *  生成提示词。
     *
     * @return 非空白提示词
     */
    private final String prompt;

    /** 参考图地址列表（公开 URL 或 data URL），非 null，可为空。
     * -- GETTER --
     *  参考图地址列表。
     *
     * @return 不可修改列表，非 null
     */
    private final List<String> references;

    /** 过程消息接收方，可为 null。
     * -- GETTER --
     *  过程消息接收方。
     *
     * @return 可为 null
     */
    private final Consumer<String> progress;

    /**
     * 创建视频生成请求。
     *
     * @param prompt     提示词，不可为空白
     * @param references 参考图地址，可为 null（视为空）
     * @param progress   过程消息接收方，可为 null
     * @throws IllegalArgumentException 提示词为空白时
     */
    public VideoGenerationRequest(String prompt, List<String> references, Consumer<String> progress) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("video prompt must not be blank");
        }
        this.prompt = prompt.trim();
        this.references = references == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(references));
        this.progress = progress;
    }

}
