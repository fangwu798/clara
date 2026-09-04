package com.clara.media.job.callback;

import com.clara.media.job.MediaProviderJobSnapshot;

public interface MediaProviderCallbackParser {
    MediaProviderJobSnapshot parse(byte[] payload);
}
