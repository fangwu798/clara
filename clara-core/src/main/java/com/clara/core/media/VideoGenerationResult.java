package com.clara.core.media;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频生成结果：任务标识、终态、视频地址与平台用量。
 */
@Getter
public final class VideoGenerationResult {

    /** 平台任务标识。
     * -- GETTER --
     *  平台任务标识。
     *
     * @return 可为 null
     */
    private final String taskId;

    /** 平台任务终态。
     * -- GETTER --
     *  平台任务终态。
     *
     * @return 可为 null
     */
    private final String status;

    /** 视频地址列表，非 null，至少一条。
     * -- GETTER --
     *  视频地址列表。
     *
     * @return 不可修改列表，非 null 且非空
     */
    private final List<String> videoUrls;

    /** 平台返回的用量信息，非 null，可为空。
     * -- GETTER --
     *  平台用量信息。
     *
     * @return 不可修改映射，非 null
     */
    private final Map<String, Object> usage;

    /**
     * 创建视频生成结果。
     *
     * @param taskId    平台任务标识，可为 null
     * @param status    平台终态，可为 null
     * @param videoUrls 视频地址列表，不可为 null 或空
     * @param usage     用量信息，可为 null（视为空）
     * @throws IllegalArgumentException 视频地址为空时
     */
    public VideoGenerationResult(String taskId, String status, List<String> videoUrls, Map<String, Object> usage) {
        if (videoUrls == null || videoUrls.isEmpty()) {
            throw new IllegalArgumentException("videoUrls must not be empty");
        }
        this.taskId = taskId;
        this.status = status;
        this.videoUrls = Collections.unmodifiableList(new ArrayList<>(videoUrls));
        this.usage = usage == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(usage));
    }

}
