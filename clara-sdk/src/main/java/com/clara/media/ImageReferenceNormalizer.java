package com.clara.media;

import com.clara.kernel.message.provider.ImageSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流附件引用归一化：把本地图片路径转成 data URI。
 *
 * <p>火山方舟只接受公网 URL 或 data URI，本地路径会被平台忽略并返回空图片列表；OpenAI 虽然可以直接
 * 读取本地文件，但统一成 data URI 后主备模型都能使用同一份引用，避免备用链路再次失败。
 */
public final class ImageReferenceNormalizer {

    private ImageReferenceNormalizer() {
    }

    /**
     * 把列表中的本地文件 / 裸 Base64 引用转成 data URI，URL 与 data URI 原样保留。
     *
     * @param references 原始引用，可为 null 或空
     * @return 归一化后的引用；输入为 null 或空时原样返回
     * @throws IllegalStateException 本地文件不存在、读取失败或超过大小上限时
     */
    public static List<String> normalize(List<String> references) {
        if (references == null || references.isEmpty()) {
            return references;
        }
        List<String> normalized = new ArrayList<>(references.size());
        int index = 0;
        for (String reference : references) {
            if (reference == null || reference.trim().isEmpty()) {
                continue;
            }
            index++;
            String value = reference.trim();
            if (ImageSource.isHttpUrl(value) || ImageSource.isDataUri(value)) {
                normalized.add(value);
            } else {
                normalized.add(ImageSource.read(value, index).toDataUri());
            }
        }
        return normalized;
    }
}
