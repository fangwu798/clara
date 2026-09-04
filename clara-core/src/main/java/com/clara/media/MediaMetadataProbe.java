package com.clara.media;

import java.io.IOException;

public interface MediaMetadataProbe {
    boolean supports(MediaType mediaType, String mimeType);
    MediaMetadata probe(MediaType mediaType, String mimeType, MediaInput content) throws IOException;
}
