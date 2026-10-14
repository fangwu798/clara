package com.clara.media;

import java.io.IOException;

/**
 * 探测并报告媒体元数据。
 */
public interface MediaMetadataProbe {
    boolean supports(MediaType mediaType, String mimeType);
    MediaMetadata probe(MediaType mediaType, String mimeType, MediaInput content) throws IOException;
}
