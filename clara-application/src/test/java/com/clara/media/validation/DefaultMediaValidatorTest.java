package com.clara.media.validation;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.media.MediaContentSafetyScanner;
import com.clara.media.MediaInput;
import com.clara.media.MediaMetadata;
import com.clara.media.MediaMetadataProbe;
import com.clara.media.MediaSafetyAssessment;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import com.clara.media.MediaValidation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class DefaultMediaValidatorTest {
    private static final byte[] MP3 = new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0};

    @Test public void usesVerifiedMetadataAndSafetyScannerWithReopenableInput() throws Exception {
        final int[] opens = new int[1];
        MediaInput input = input(MP3, opens);
        MediaMetadataProbe probe = probe(1200L);
        MediaContentSafetyScanner scanner = new MediaContentSafetyScanner() {
            public MediaSafetyAssessment scan(MediaUpload upload, MediaValidation validation, MediaInput content)
                    throws IOException {
                assertEquals(1200L, validation.getDurationMs().longValue());
                InputStream stream = content.open();
                try { assertEquals('I', stream.read()); } finally { stream.close(); }
                return MediaSafetyAssessment.allowed();
            }
        };
        DefaultMediaValidator validator = new DefaultMediaValidator(policy(true, true),
                Collections.singletonList(probe), scanner);
        MediaValidation result = validator.validate(upload(null, input), input, MP3.length, 0);
        assertEquals(1200L, result.getDurationMs().longValue());
        assertEquals(3, opens[0]);
    }

    @Test public void failsClosedWhenRequiredInspectionCapabilitiesAreMissing() {
        final MediaInput input = input(MP3, new int[1]);
        final DefaultMediaValidator noProbe = new DefaultMediaValidator(policy(true, false));
        assertCode(ErrorCodes.MEDIA_METADATA_UNSUPPORTED, new Runnable() { public void run() {
            validate(noProbe, upload(null, input), input);
        }});
        try { new DefaultMediaValidator(policy(false, true)); fail("expected missing scanner"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsUnsafeContentAndDeclaredMetadataMismatch() {
        final MediaInput input = input(MP3, new int[1]);
        MediaContentSafetyScanner rejecting = new MediaContentSafetyScanner() {
            public MediaSafetyAssessment scan(MediaUpload upload, MediaValidation validation, MediaInput content) {
                return MediaSafetyAssessment.rejected("policy");
            }
        };
        final DefaultMediaValidator unsafe = new DefaultMediaValidator(policy(true, true),
                Collections.singletonList(probe(1200L)), rejecting);
        assertCode(ErrorCodes.MEDIA_CONTENT_REJECTED, new Runnable() { public void run() {
            validate(unsafe, upload(null, input), input);
        }});
        final DefaultMediaValidator mismatch = new DefaultMediaValidator(policy(true, false),
                Collections.singletonList(probe(1200L)), null);
        assertCode(ErrorCodes.MEDIA_VALIDATION_FAILED, new Runnable() { public void run() {
            validate(mismatch, upload(Long.valueOf(999), input), input);
        }});
    }

    private static MediaValidationPolicy policy(boolean metadata, boolean safety) {
        Map<String, String> extensions = new LinkedHashMap<String, String>();
        extensions.put(".mp3", "audio/mpeg");
        Map<String, List<String>> magic = new LinkedHashMap<String, List<String>>();
        magic.put("audio/mpeg", Collections.singletonList("494433"));
        return new MediaValidationPolicy(1000, 2000, 60000, 100, 100, 5000,
                extensions, magic, metadata, safety);
    }
    private static MediaMetadataProbe probe(final long duration) {
        return new MediaMetadataProbe() {
            public boolean supports(MediaType type, String mime) {
                return type == MediaType.AUDIO && "audio/mpeg".equals(mime);
            }
            public MediaMetadata probe(MediaType type, String mime, MediaInput content) throws IOException {
                InputStream stream = content.open();
                try { assertEquals('I', stream.read()); } finally { stream.close(); }
                return new MediaMetadata(null, null, Long.valueOf(duration));
            }
        };
    }
    private static MediaUpload upload(Long duration, MediaInput input) {
        return new MediaUpload("tenant", MediaType.AUDIO, "audio.mp3", "audio/mpeg", null,
                null, null, duration, null, Collections.<String, String>emptyMap(), input);
    }
    private static MediaInput input(final byte[] bytes, final int[] opens) {
        return new MediaInput() { public InputStream open() { opens[0]++; return new ByteArrayInputStream(bytes); } };
    }
    private static void validate(DefaultMediaValidator validator, MediaUpload upload, MediaInput input) {
        try { validator.validate(upload, input, MP3.length, 0); }
        catch (IOException e) { throw new AssertionError(e); }
    }
    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException e) { assertEquals(code, e.getError().getCode()); }
    }
}
