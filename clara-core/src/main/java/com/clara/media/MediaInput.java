package com.clara.media;

import java.io.IOException;
import java.io.InputStream;

public interface MediaInput {
    InputStream open() throws IOException;
}
