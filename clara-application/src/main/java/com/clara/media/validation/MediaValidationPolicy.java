package com.clara.media.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 定义媒体校验需要遵循的策略。
 */
public final class MediaValidationPolicy {
    private final long maxFileBytes;
    private final long maxTenantBytes;
    private final long retentionMillis;
    private final int maxImageWidth;
    private final int maxImageHeight;
    private final long maxDurationMs;
    private final Map<String, String> extensionMimeTypes;
    private final Map<String, List<String>> magicPrefixes;
    private final boolean verifiedMetadataRequired;
    private final boolean contentSafetyRequired;

    public MediaValidationPolicy(long maxFileBytes, long maxTenantBytes, long retentionMillis,
                                 int maxImageWidth, int maxImageHeight, long maxDurationMs,
                                 Map<String, String> extensionMimeTypes, Map<String, List<String>> magicPrefixes) {
        this(maxFileBytes, maxTenantBytes, retentionMillis, maxImageWidth, maxImageHeight,
                maxDurationMs, extensionMimeTypes, magicPrefixes, false, false);
    }

    public MediaValidationPolicy(long maxFileBytes, long maxTenantBytes, long retentionMillis,
                                 int maxImageWidth, int maxImageHeight, long maxDurationMs,
                                 Map<String, String> extensionMimeTypes, Map<String, List<String>> magicPrefixes,
                                 boolean verifiedMetadataRequired, boolean contentSafetyRequired) {
        if (maxFileBytes <= 0 || maxTenantBytes <= 0 || retentionMillis <= 0
                || maxImageWidth <= 0 || maxImageHeight <= 0 || maxDurationMs <= 0) {
            throw new IllegalArgumentException("media limits must be positive");
        }
        if (maxFileBytes > maxTenantBytes) {
            throw new IllegalArgumentException("file limit exceeds tenant limit");
        }
        if (extensionMimeTypes == null || extensionMimeTypes.isEmpty()) {
            throw new IllegalArgumentException("extensionMimeTypes must not be empty");
        }
        if (magicPrefixes == null || magicPrefixes.isEmpty()) {
            throw new IllegalArgumentException("magicPrefixes must not be empty");
        }
        this.maxFileBytes = maxFileBytes;
        this.maxTenantBytes = maxTenantBytes;
        this.retentionMillis = retentionMillis;
        this.maxImageWidth = maxImageWidth;
        this.maxImageHeight = maxImageHeight;
        this.maxDurationMs = maxDurationMs;
        this.verifiedMetadataRequired = verifiedMetadataRequired;
        this.contentSafetyRequired = contentSafetyRequired;
        Map<String, String> extensions = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : extensionMimeTypes.entrySet())
            extensions.put(normalizeExtension(entry.getKey()), normalizeMime(entry.getValue()));
        this.extensionMimeTypes = Collections.unmodifiableMap(extensions);
        Map<String, List<String>> signatures = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : magicPrefixes.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                throw new IllegalArgumentException("magic prefix list must not be empty");
            }
            List<String> values = new ArrayList<String>();
            for (String value : entry.getValue()) values.add(normalizeHex(value));
            signatures.put(normalizeMime(entry.getKey()), Collections.unmodifiableList(values));
        }
        this.magicPrefixes = Collections.unmodifiableMap(signatures);
    }

    private static String normalizeExtension(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("extension must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.charAt(0) == '.' ? normalized : "." + normalized;
    }

    private static String normalizeMime(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("mime type must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeHex(String value) {
        if (value == null) {
            throw new IllegalArgumentException("magic prefix must not be null");
        }
        String normalized = value.replace(" ", "").toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || (normalized.length() & 1) != 0 || !normalized.matches("[0-9A-F]+")) {
            throw new IllegalArgumentException("magic prefix must contain complete hexadecimal bytes");
        }
        return normalized;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public long getMaxTenantBytes() {
        return maxTenantBytes;
    }

    public long getRetentionMillis() {
        return retentionMillis;
    }

    public int getMaxImageWidth() {
        return maxImageWidth;
    }

    public int getMaxImageHeight() {
        return maxImageHeight;
    }

    public long getMaxDurationMs() {
        return maxDurationMs;
    }

    public Map<String, String> getExtensionMimeTypes() {
        return extensionMimeTypes;
    }

    public Map<String, List<String>> getMagicPrefixes() {
        return magicPrefixes;
    }

    public boolean isVerifiedMetadataRequired() {
        return verifiedMetadataRequired;
    }

    public boolean isContentSafetyRequired() {
        return contentSafetyRequired;
    }
}
