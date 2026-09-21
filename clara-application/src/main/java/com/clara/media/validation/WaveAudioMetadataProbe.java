package com.clara.media.validation;

import com.clara.kernel.exception.ClaraError;
import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ErrorCategory;
import com.clara.kernel.exception.ErrorCodes;
import com.clara.media.MediaInput;
import com.clara.media.MediaMetadata;
import com.clara.media.MediaMetadataProbe;
import com.clara.media.MediaType;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

/**
 * 探测并报告WAVE音频元数据。
 */
public final class WaveAudioMetadataProbe implements MediaMetadataProbe {
    private final int maxChunks;
    private final long maxHeaderBytes;

    public WaveAudioMetadataProbe(int maxChunks, long maxHeaderBytes) {
        if (maxChunks <= 0 || maxHeaderBytes < 44) {
            throw new IllegalArgumentException("WAV probe limits are invalid");
        }
        this.maxChunks = maxChunks;
        this.maxHeaderBytes = maxHeaderBytes;
    }

    public boolean supports(MediaType mediaType, String mimeType) {
        return mediaType == MediaType.AUDIO
                && ("audio/wav".equalsIgnoreCase(mimeType) || "audio/x-wav".equalsIgnoreCase(mimeType));
    }

    public MediaMetadata probe(MediaType mediaType, String mimeType, MediaInput content) throws IOException {
        if (!supports(mediaType, mimeType)) {
            throw new IllegalArgumentException("unsupported media type");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        InputStream opened = content.open();
        if (opened == null) {
            throw new IOException("media input returned null");
        }
        InputStream input = new BufferedInputStream(opened);
        try {
            if (!"RIFF".equals(ascii(input, 4))) invalid();
            readUnsignedInt32(input);
            if (!"WAVE".equals(ascii(input, 4))) invalid();
            long consumed = 12;
            Long byteRate = null;
            Long dataSize = null;
            for (int chunks = 0; chunks < maxChunks && consumed <= maxHeaderBytes; chunks++) {
                String id = ascii(input, 4);
                long size = readUnsignedInt32(input);
                consumed += 8;
                if (consumed > maxHeaderBytes) invalid();
                if ("fmt ".equals(id)) {
                    if (size < 16 || size > maxHeaderBytes - consumed) invalid();
                    int format = readUnsignedInt16(input);
                    int channels = readUnsignedInt16(input);
                    long sampleRate = readUnsignedInt32(input);
                    long detectedByteRate = readUnsignedInt32(input);
                    int blockAlign = readUnsignedInt16(input);
                    int bitsPerSample = readUnsignedInt16(input);
                    if (format == 0 || channels == 0 || sampleRate == 0 || detectedByteRate == 0
                            || blockAlign == 0 || bitsPerSample == 0) invalid();
                    byteRate = Long.valueOf(detectedByteRate);
                    skipFully(input, size - 16);
                } else if ("data".equals(id)) {
                    dataSize = Long.valueOf(size);
                    if (byteRate != null) return metadata(dataSize.longValue(), byteRate.longValue());
                    if (size > maxHeaderBytes - consumed) invalid();
                    skipFully(input, size);
                } else {
                    if (size > maxHeaderBytes - consumed) invalid();
                    skipFully(input, size);
                }
                consumed += size;
                if ((size & 1) != 0) {
                    skipFully(input, 1);
                    consumed++;
                }
                if (byteRate != null && dataSize != null) return metadata(dataSize.longValue(), byteRate.longValue());
            }
            invalid();
            return null;
        } finally {
            input.close();
        }
    }

    private static MediaMetadata metadata(long dataSize, long byteRate) {
        long duration = (dataSize / byteRate) * 1000L + ((dataSize % byteRate) * 1000L) / byteRate;
        return new MediaMetadata(null, null, Long.valueOf(duration));
    }

    private static int readUnsignedInt16(InputStream input) throws IOException {
        int first = input.read();
        int second = input.read();
        if ((first | second) < 0) invalid();
        return first | (second << 8);
    }

    private static long readUnsignedInt32(InputStream input) throws IOException {
        long first = input.read();
        long second = input.read();
        long third = input.read();
        long fourth = input.read();
        if ((first | second | third | fourth) < 0) invalid();
        return first | (second << 8) | (third << 16) | (fourth << 24);
    }

    private static String ascii(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) invalid();
            offset += read;
        }
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) invalid();
            remaining--;
        }
    }

    private static void invalid() {
        throw new ClaraException(new ClaraError(ErrorCodes.MEDIA_VALIDATION_FAILED, ErrorCategory.VALIDATION,
                ErrorCodes.MEDIA_VALIDATION_FAILED, false, null, null, null, null,
                Collections.<String, String>emptyMap()));
    }
}
