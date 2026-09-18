package com.clara.model.provider.compatible.config;

import com.clara.model.provider.compatible.OpenAiCompatibleOptions;

/**
 * 定义 OpenAI 兼容协议中「推理内容」扩展字段的固定名称及其归属。
 *
 * <p>归属说明（重要）：{@code reasoning_content} 是 OpenAI 兼容家族<b>通用</b>的推理内容扩展字段，
 * 并非某个平台私有——DeepSeek、火山引擎、阿里等平台都返回同名字段。因此它的解析由本包统一负责，
 * 各平台无需重复实现；这与请求侧 DeepSeek 独有的前缀补全字段（{@code prefix}）不同，后者由平台包
 * 通过 {@link OpenAiCompatibleOptions} 自行写入。
 *
 * <p>{@link #METADATA_KEY} 是领域层 {@code ChatResponse} 元数据中的键名，非流式响应与流式收尾块共用，
 * 保证两种调用方式产出的元数据一致。
 */
public final class OpenAiCompatibleReasoning {
    /**
     * 响应 message 与流式 delta 中承载推理内容的字段名。
     */
    public static final String CONTENT_FIELD = "reasoning_content";
    /**
     * 领域响应元数据中承载完整推理内容的键名。
     */
    public static final String METADATA_KEY = "reasoning";

}
