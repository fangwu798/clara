package com.clara.media.filesystem;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.media.MediaAsset;
import com.clara.media.MediaInput;
import com.clara.media.MediaContentSafetyScanner;
import com.clara.media.MediaSafetyAssessment;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import com.clara.media.MediaValidation;
import com.clara.media.validation.MediaValidationPolicy;
import com.clara.media.validation.WaveAudioMetadataProbe;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FileSystemMediaStoreTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    @Test public void storesDeterministicAssetAndReopensManifest() throws Exception {
        Path workspace = folder.newFolder("workspace").toPath();
        byte[] png = png(2, 3, Color.RED);
        FileSystemMediaStore store = new FileSystemMediaStore(config(workspace, 100000, 200000));
        MediaAsset first = store.put(upload("tenant-a", "image.png", "image/png", png));
        MediaAsset repeated = store.put(upload("tenant-a", "renamed.png", "image/png", png));
        assertEquals(first.getAssetId(), repeated.getAssetId());
        assertEquals(2, first.getWidth().intValue());
        assertEquals(3, first.getHeight().intValue());
        assertArrayEquals(png, read(store.open("tenant-a", first.getAssetId())));
        store.close();

        FileSystemMediaStore reopened = new FileSystemMediaStore(config(workspace, 100000, 200000));
        assertEquals(first.getSha256(), reopened.get("tenant-a", first.getAssetId()).getSha256());
        assertCode(ErrorCodes.MEDIA_NOT_FOUND, new Runnable() { public void run() { reopened.get("tenant-b", first.getAssetId()); }});
        reopened.close();
    }

    @Test public void rejectsMimeMagicSizeAndTenantQuotaViolations() throws Exception {
        Path workspace = folder.newFolder("limits").toPath();
        final byte[] png = png(2, 2, Color.BLUE);
        final FileSystemMediaStore store = new FileSystemMediaStore(config(workspace, png.length + 5, png.length + 5));
        assertCode(ErrorCodes.MEDIA_VALIDATION_FAILED, new Runnable() { public void run() {
            store.put(upload("tenant", "wrong.jpg", "image/jpeg", png));
        }});
        assertCode(ErrorCodes.MEDIA_VALIDATION_FAILED, new Runnable() { public void run() {
            store.put(upload("tenant", "wrong.png", "image/png", new byte[] {(byte) 0x89, 1, 2}));
        }});
        store.put(upload("tenant", "one.png", "image/png", png));
        final byte[] other = png(3, 2, Color.GREEN);
        assertCode(ErrorCodes.MEDIA_RESOURCE_LIMIT, new Runnable() { public void run() {
            store.put(upload("tenant", "two.png", "image/png", other));
        }});
        store.close();
    }

    @Test public void removesExpiredAssetsWithExplicitCleanup() throws Exception {
        Path workspace = folder.newFolder("expiry").toPath();
        FileSystemMediaStore store = new FileSystemMediaStore(config(workspace, 100000, 200000));
        MediaAsset asset = store.put(upload("tenant", "image.png", "image/png", png(1, 1, Color.BLACK)));
        assertEquals(1, store.cleanupExpired(Long.MAX_VALUE));
        final String id = asset.getAssetId();
        assertCode(ErrorCodes.MEDIA_NOT_FOUND, new Runnable() { public void run() { store.get("tenant", id); }});
        store.close();
    }

    @Test public void persistsVerifiedWaveDurationAfterSafetyScan() throws Exception {
        Path workspace = folder.newFolder("wave").toPath();
        Map<String, String> extensions = Collections.singletonMap(".wav", "audio/wav");
        Map<String, java.util.List<String>> magic = Collections.singletonMap("audio/wav",
                Collections.singletonList("52494646"));
        MediaValidationPolicy policy = new MediaValidationPolicy(50000, 100000, 60000,
                100, 100, 5000, extensions, magic, true, true);
        final boolean[] scanned = new boolean[1];
        MediaContentSafetyScanner scanner = new MediaContentSafetyScanner() {
            public MediaSafetyAssessment scan(MediaUpload upload, MediaValidation validation, MediaInput content) {
                scanned[0] = true;
                return MediaSafetyAssessment.allowed();
            }
        };
        FileSystemMediaStore store = FileSystemMediaStore.inspected(
                new FileSystemMediaStoreConfig(workspace, policy),
                Collections.singletonList(new WaveAudioMetadataProbe(8, 1024)), scanner);
        final byte[] wave = wave(8000, 16000);
        MediaUpload upload = new MediaUpload("tenant", MediaType.AUDIO, "sample.wav", "audio/wav",
                null, null, null, null, null, Collections.<String, String>emptyMap(), new MediaInput() {
                    public InputStream open() { return new ByteArrayInputStream(wave); }
                });
        MediaAsset asset = store.put(upload);
        assertEquals(1000L, asset.getDurationMs().longValue());
        assertTrue(scanned[0]);
        store.close();
    }

    private static FileSystemMediaStoreConfig config(Path workspace, long fileLimit, long tenantLimit) {
        Map<String, String> extensions = new LinkedHashMap<String, String>();
        extensions.put(".png", "image/png"); extensions.put(".jpg", "image/jpeg");
        Map<String, java.util.List<String>> magic = new LinkedHashMap<String, java.util.List<String>>();
        magic.put("image/png", Collections.singletonList("89504E470D0A1A0A"));
        magic.put("image/jpeg", Collections.singletonList("FFD8FF"));
        return new FileSystemMediaStoreConfig(workspace, fileLimit, tenantLimit, 60000,
                100, 100, 60000, extensions, magic);
    }
    private static MediaUpload upload(String tenant, String name, String mime, final byte[] bytes) {
        return new MediaUpload(tenant, MediaType.IMAGE, name, mime, null, null, null, null, null,
                Collections.singletonMap("kind", "test"), new MediaInput() {
                    public InputStream open() { return new ByteArrayInputStream(bytes); }
                });
    }
    private static byte[] png(int width, int height, Color color) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            try { graphics.setColor(color); graphics.fillRect(0, 0, width, height); } finally { graphics.dispose(); }
            ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
        } catch (IOException e) { throw new AssertionError(e); }
    }
    private static byte[] wave(int sampleRate, int dataBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); le32(output, 36L + dataBytes);
        output.write("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); le32(output, 16);
        le16(output, 1); le16(output, 1); le32(output, sampleRate); le32(output, sampleRate * 2L);
        le16(output, 2); le16(output, 16); output.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        le32(output, dataBytes); output.write(new byte[dataBytes]); return output.toByteArray();
    }
    private static void le16(ByteArrayOutputStream output, int value) { output.write(value); output.write(value >>> 8); }
    private static void le32(ByteArrayOutputStream output, long value) {
        output.write((int) value); output.write((int) (value >>> 8)); output.write((int) (value >>> 16)); output.write((int) (value >>> 24));
    }
    private static byte[] read(InputStream input) throws IOException {
        try { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[128]; int count; while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count); return output.toByteArray(); }
        finally { input.close(); }
    }
    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException e) { assertEquals(code, e.getError().getCode()); }
    }
}
