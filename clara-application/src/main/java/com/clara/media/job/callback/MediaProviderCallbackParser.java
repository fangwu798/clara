package com.clara.media.job.callback;

import com.clara.media.job.MediaProviderJobSnapshot;

/**
 * 将媒体服务提供商回调数据解析为领域对象。
 */
public interface MediaProviderCallbackParser {
    MediaProviderJobSnapshot parse(byte[] payload);
}
