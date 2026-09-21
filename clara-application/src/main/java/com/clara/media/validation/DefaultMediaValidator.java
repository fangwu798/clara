package com.clara.media.validation;

import com.clara.kernel.exception.ClaraError;
import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ErrorCategory;
import com.clara.kernel.exception.ErrorCodes;
import com.clara.media.MediaContentSafetyScanner;
import com.clara.media.MediaInput;
import com.clara.media.MediaMetadata;
import com.clara.media.MediaMetadataProbe;
import com.clara.media.MediaSafetyAssessment;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import com.clara.media.MediaValidation;
import com.clara.media.MediaValidator;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * 按媒体策略校验文件大小、租户配额、扩展名、MIME、魔数、元数据和内容安全结果。
 *
 * <p>常规用法：仅做基础校验时传入 {@link MediaValidationPolicy}；音视频需要可信元数据时注册对应的
 * {@link MediaMetadataProbe}，策略要求内容安全时还必须提供 {@link MediaContentSafetyScanner}。
 *
 * <p>注意：{@link #validate} 可能多次打开 {@link MediaInput}，输入实现必须可重复读取并为每次调用返回新流。
 * 同一媒体类型和 MIME 最多只能匹配一个探测器；声明元数据与探测结果不一致会直接拒绝上传。
 */
public final class DefaultMediaValidator implements MediaValidator {
    private final MediaValidationPolicy config;
    private final List<MediaMetadataProbe> metadataProbes;
    private final MediaContentSafetyScanner safetyScanner;

    public DefaultMediaValidator(MediaValidationPolicy config) {
        this(config, Collections.<MediaMetadataProbe>emptyList(), null);
    }

    public DefaultMediaValidator(MediaValidationPolicy config, List<MediaMetadataProbe> metadataProbes,
                                 MediaContentSafetyScanner safetyScanner) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        List<MediaMetadataProbe> safe = metadataProbes == null
                ? Collections.<MediaMetadataProbe>emptyList() : metadataProbes;
        if (safe.contains(null)) {
            throw new IllegalArgumentException("metadataProbes must not contain null");
        }
        if (config.isContentSafetyRequired() && safetyScanner == null) {
            throw new IllegalArgumentException("content safety scanner is required by policy");
        }
        this.config = config;
        this.metadataProbes = Collections.unmodifiableList(new ArrayList<MediaMetadataProbe>(safe));
        this.safetyScanner = safetyScanner;
    }

    public MediaValidation validate(MediaUpload upload, MediaInput content, long size, long tenantUsage)
            throws IOException {
        if (upload == null || content == null) {
            throw new IllegalArgumentException("upload and content must not be null");
        }
        if (size > config.getMaxFileBytes() || tenantUsage > config.getMaxTenantBytes() - size)
            fail(ErrorCodes.MEDIA_RESOURCE_LIMIT, ErrorCategory.RESOURCE_LIMIT);
        String extension = extension(upload.getFileName());
        String expectedMime = config.getExtensionMimeTypes().get(extension);
        if (expectedMime == null || !expectedMime.equals(upload.getMimeType())) invalid();
        if (!matchesMediaType(upload.getMediaType(), expectedMime)) invalid();
        List<String> prefixes = config.getMagicPrefixes().get(expectedMime);
        if (prefixes == null || prefixes.isEmpty()) invalid();

        Integer width = upload.getWidth();
        Integer height = upload.getHeight();
        Long duration = upload.getDurationMs();
        InputStream opened = content.open();
        if (opened == null) {
            throw new IOException("media input returned null");
        }
        BufferedInputStream buffered = new BufferedInputStream(opened);
        try {
            int max = 0;
            for (String prefix : prefixes) max = Math.max(max, prefix.length() / 2);
            buffered.mark(Math.max(max, 64 * 1024));
            byte[] header = new byte[max];
            int count = readUpTo(buffered, header);
            buffered.reset();
            boolean matched = false;
            for (String prefix : prefixes)
                if (matches(header, count, prefix)) {
                    matched = true;
                    break;
                }
            if (!matched) invalid();
            if (upload.getMediaType() == MediaType.IMAGE) {
                BufferedImage image = ImageIO.read(buffered);
                if (image == null) invalid();
                width = Integer.valueOf(image.getWidth());
                height = Integer.valueOf(image.getHeight());
                compareDeclared(upload, new MediaMetadata(width, height, duration));
            }
        } finally {
            buffered.close();
        }

        if (upload.getMediaType() == MediaType.AUDIO || upload.getMediaType() == MediaType.VIDEO) {
            MediaMetadataProbe probe = selectProbe(upload.getMediaType(), expectedMime);
            if (probe == null) {
                if (config.isVerifiedMetadataRequired()) metadataUnsupported();
            } else {
                MediaMetadata detected = probe.probe(upload.getMediaType(), expectedMime, content);
                if (detected == null) invalid();
                compareDeclared(upload, detected);
                width = detected.getWidth();
                height = detected.getHeight();
                duration = detected.getDurationMs();
            }
            if (config.isVerifiedMetadataRequired()
                    && (duration == null || (upload.getMediaType() == MediaType.VIDEO && width == null)))
                metadataUnsupported();
        }
        enforceMetadataLimits(width, height, duration);
        MediaValidation validation = new MediaValidation(expectedMime, width, height, duration);
        if (safetyScanner != null) {
            MediaSafetyAssessment assessment = safetyScanner.scan(upload, validation, content);
            if (assessment == null) invalid();
            if (!assessment.isAllowed()) rejected();
        }
        return validation;
    }

    private MediaMetadataProbe selectProbe(MediaType type, String mime) {
        MediaMetadataProbe selected = null;
        for (MediaMetadataProbe probe : metadataProbes)
            if (probe.supports(type, mime)) {
                if (selected != null) invalid();
                selected = probe;
            }
        return selected;
    }

    private void enforceMetadataLimits(Integer width, Integer height, Long duration) {
        if (width != null && (width.intValue() > config.getMaxImageWidth()
                || height.intValue() > config.getMaxImageHeight()))
            fail(ErrorCodes.MEDIA_RESOURCE_LIMIT, ErrorCategory.RESOURCE_LIMIT);
        if (duration != null && duration.longValue() > config.getMaxDurationMs())
            fail(ErrorCodes.MEDIA_RESOURCE_LIMIT, ErrorCategory.RESOURCE_LIMIT);
    }

    private static void compareDeclared(MediaUpload upload, MediaMetadata detected) {
        if ((upload.getWidth() != null && !upload.getWidth().equals(detected.getWidth()))
                || (upload.getHeight() != null && !upload.getHeight().equals(detected.getHeight()))
                || (upload.getDurationMs() != null && !upload.getDurationMs().equals(detected.getDurationMs())))
            invalid();
    }

    private static int readUpTo(InputStream input, byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int read = input.read(bytes, offset, bytes.length - offset);
            if (read < 0) break;
            offset += read;
        }
        return offset;
    }

    private static boolean matches(byte[] header, int count, String hex) {
        int length = hex.length() / 2;
        if (count < length) return false;
        for (int i = 0; i < length; i++)
            if ((header[i] & 0xff) != Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16)) return false;
        return true;
    }

    private static boolean matchesMediaType(MediaType type, String mime) {
        if (type == MediaType.IMAGE) return mime.startsWith("image/");
        if (type == MediaType.AUDIO) return mime.startsWith("audio/");
        if (type == MediaType.VIDEO) return mime.startsWith("video/");
        if (type == MediaType.DOCUMENT) return mime.startsWith("application/") || mime.startsWith("text/");
        return true;
    }

    private static String extension(String fileName) {
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        int dot = fileName.lastIndexOf('.');
        if (dot <= slash || dot == fileName.length() - 1) invalid();
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static void invalid() {
        fail(ErrorCodes.MEDIA_VALIDATION_FAILED, ErrorCategory.VALIDATION);
    }

    private static void metadataUnsupported() {
        fail(ErrorCodes.MEDIA_METADATA_UNSUPPORTED, ErrorCategory.CAPABILITY);
    }

    private static void rejected() {
        fail(ErrorCodes.MEDIA_CONTENT_REJECTED, ErrorCategory.CONTENT_SAFETY);
    }

    private static void fail(String code, ErrorCategory category) {
        throw new ClaraException(new ClaraError(code, category, code, false, null, null, null, null,
                Collections.<String, String>emptyMap()));
    }
}
