package com.clara.novel;

import com.clara.kernel.InvocationContext;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Stores a generated cover directly in a novel project's output directory. */
public final class NovelCoverAssetBridge implements ProviderMediaAssetBridge {
    private static final int MAX_COVER_BYTES = 32 * 1024 * 1024;
    private final Path outputDirectory;

    public NovelCoverAssetBridge(Path outputDirectory) {
        if (outputDirectory == null) throw new IllegalArgumentException("outputDirectory must not be null");
        this.outputDirectory = outputDirectory;
    }

    public String storeBytes(byte[] content, MediaType type, String mimeType,
            InvocationContext context) {
        if (content == null || content.length == 0 || content.length > MAX_COVER_BYTES) {
            throw new IllegalArgumentException("generated cover has an invalid size");
        }
        requireImage(type);
        writeCover(content);
        return "cover.png";
    }

    public String storeRemote(String sourceUrl, MediaType type, String mimeType,
            InvocationContext context) {
        requireImage(type);
        throw new UnsupportedOperationException("remote cover URLs are not accepted");
    }

    public String inputReference(String assetId, MediaType expectedType, InvocationContext context) {
        throw new UnsupportedOperationException("cover editing is not supported");
    }
    public byte[] inputBytes(String assetId, MediaType expectedType, int maxBytes,
            InvocationContext context) {
        throw new UnsupportedOperationException("cover editing is not supported");
    }
    public MediaUpload remoteUpload(String sourceUrl, MediaType type, String mimeType,
            InvocationContext context) {
        throw new UnsupportedOperationException("remote uploads are not supported");
    }

    private void writeCover(byte[] content) {
        try {
            Files.createDirectories(outputDirectory);
            Path target = outputDirectory.resolve("cover.png");
            Path temporary = outputDirectory.resolve("cover.png.tmp");
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot persist generated cover", exception);
        }
    }

    private static void requireImage(MediaType type) {
        if (type != MediaType.IMAGE) throw new IllegalArgumentException("only image output is supported");
    }
}
