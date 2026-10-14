package com.clara.media;

import java.io.IOException;
import java.io.InputStream;

/**
 * 定义。
 */
public interface MediaInput {
    InputStream open() throws IOException;
}
