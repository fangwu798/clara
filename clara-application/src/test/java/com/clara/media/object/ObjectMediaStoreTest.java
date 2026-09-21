package com.clara.media.object;

import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ErrorCodes;
import com.clara.media.MediaAsset;
import com.clara.media.MediaInput;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import com.clara.media.validation.MediaValidationPolicy;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.*;

public class ObjectMediaStoreTest {
    @Test public void stagesValidatesPublishesAndIsolatesTenants() throws Exception {
        FakeBackend backend = new FakeBackend();
        ObjectMediaStore store = new ObjectMediaStore(config(100000, 200000), backend);
        byte[] content = png(2, 3, Color.RED);
        MediaAsset asset = store.put(upload("tenant-a", "image.png", "image/png", content));
        assertEquals(asset.getSha256(), asset.getAssetId());
        assertEquals(2, asset.getWidth().intValue());
        assertEquals(0, backend.stagedCount());
        assertArrayEquals(content, read(store.open("tenant-a", asset.getAssetId())));
        final String id = asset.getAssetId();
        assertCode(ErrorCodes.MEDIA_NOT_FOUND, new Runnable() { public void run() { store.get("tenant-b", id); }});
        assertEquals(id, store.put(upload("tenant-a", "copy.png", "image/png", content)).getAssetId());
        store.close();
    }

    @Test public void enforcesValidationAndAtomicBackendQuota() throws Exception {
        byte[] first = png(2, 2, Color.BLUE);
        FakeBackend backend = new FakeBackend();
        final ObjectMediaStore store = new ObjectMediaStore(config(first.length + 4, first.length + 4), backend);
        assertCode(ErrorCodes.MEDIA_VALIDATION_FAILED, new Runnable() { public void run() {
            store.put(upload("tenant", "bad.jpg", "image/jpeg", first));
        }});
        assertEquals(0, backend.stagedCount());
        store.put(upload("tenant", "one.png", "image/png", first));
        final byte[] second = png(3, 2, Color.GREEN);
        assertCode(ErrorCodes.MEDIA_RESOURCE_LIMIT, new Runnable() { public void run() {
            store.put(upload("tenant", "two.png", "image/png", second));
        }});
        assertEquals(0, backend.stagedCount());
        store.close();
    }

    @Test public void rejectsBackendIntegrityFailuresAndMapsRetryability() throws Exception {
        final FakeBackend corrupt = new FakeBackend(); corrupt.corruptStageDigest = true;
        final ObjectMediaStore corruptStore = new ObjectMediaStore(config(100000, 200000), corrupt);
        assertCode(ErrorCodes.MEDIA_BACKEND_FAILED, new Runnable() { public void run() {
            corruptStore.put(upload("tenant", "image.png", "image/png", png(1, 1, Color.BLACK)));
        }});
        assertEquals(0, corrupt.stagedCount());
        corruptStore.close();

        final FakeBackend failing = new FakeBackend(); failing.failHead = true;
        final ObjectMediaStore failingStore = new ObjectMediaStore(config(100000, 200000), failing);
        try { failingStore.get("tenant", repeat('a', 64)); fail("expected backend failure"); }
        catch (ClaraException e) { assertEquals(ErrorCodes.MEDIA_BACKEND_FAILED, e.getError().getCode()); assertTrue(e.getError().isRetryable()); }
        failingStore.close();
    }

    @Test public void cleansExpiredPublishedObjects() throws Exception {
        FakeBackend backend = new FakeBackend();
        ObjectMediaStore store = new ObjectMediaStore(config(100000, 200000), backend);
        MediaAsset asset = store.put(upload("tenant", "image.png", "image/png", png(1, 1, Color.WHITE)));
        assertEquals(1, store.cleanupExpired(Long.MAX_VALUE));
        final String id = asset.getAssetId();
        assertCode(ErrorCodes.MEDIA_NOT_FOUND, new Runnable() { public void run() { store.get("tenant", id); }});
        store.close();
    }

    private static ObjectMediaStoreConfig config(long fileLimit, long tenantLimit) {
        Map<String, String> extensions = new LinkedHashMap<String, String>();
        extensions.put(".png", "image/png"); extensions.put(".jpg", "image/jpeg");
        Map<String, List<String>> magic = new LinkedHashMap<String, List<String>>();
        magic.put("image/png", Collections.singletonList("89504E470D0A1A0A"));
        magic.put("image/jpeg", Collections.singletonList("FFD8FF"));
        MediaValidationPolicy policy = new MediaValidationPolicy(fileLimit, tenantLimit, 60000,
                100, 100, 60000, extensions, magic);
        return new ObjectMediaStoreConfig("clara-test", policy);
    }
    private static MediaUpload upload(String tenant, String name, String mime, final byte[] bytes) {
        return new MediaUpload(tenant, MediaType.IMAGE, name, mime, null, null, null, null, null,
                Collections.<String, String>emptyMap(), new MediaInput() {
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
    private static byte[] read(InputStream input) throws IOException {
        try { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[128]; int count; while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count); return output.toByteArray(); }
        finally { input.close(); }
    }
    private static String repeat(char value, int count) { char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars); }
    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException e) { assertEquals(code, e.getError().getCode()); }
    }

    private static final class FakeBackend implements ObjectMediaBackend {
        private final Map<String, byte[]> content = new LinkedHashMap<String, byte[]>();
        private final Map<String, ObjectMediaRecord> records = new LinkedHashMap<String, ObjectMediaRecord>();
        private boolean corruptStageDigest;
        private boolean failHead;

        public StagedMediaObject stage(String key, InputStream source, long maxBytes) {
            try {
                byte[] bytes = read(source);
                if (bytes.length > maxBytes) throw new ObjectMediaBackendException("too large", false, true, null);
                content.put(key, bytes);
                return new StagedMediaObject(key, bytes.length, corruptStageDigest ? repeat('0', 64) : sha(bytes));
            } catch (IOException e) { throw new ObjectMediaBackendException("stage", true, e); }
        }
        public InputStream open(String key) {
            byte[] bytes = content.get(key);
            if (bytes == null) throw new ObjectMediaBackendException("missing", false, null);
            return new ByteArrayInputStream(bytes);
        }
        public ObjectMediaRecord head(String key) {
            if (failHead) throw new ObjectMediaBackendException("head", true, null);
            return records.get(key);
        }
        public List<ObjectMediaRecord> list(String prefix) {
            List<ObjectMediaRecord> values = new ArrayList<ObjectMediaRecord>();
            for (ObjectMediaRecord record : records.values()) if (record.getKey().startsWith(prefix)) values.add(record);
            return values;
        }
        public long usage(String prefix) {
            long total = 0;
            for (ObjectMediaRecord record : records.values()) if (record.getKey().startsWith(prefix)) total += record.getSize();
            return total;
        }
        public ObjectMediaRecord publishIfAbsent(String stagingKey, String destinationKey, String tenantPrefix,
                long maxTenantBytes, MediaAsset asset) {
            ObjectMediaRecord existing = records.get(destinationKey);
            if (existing != null) return existing;
            byte[] bytes = content.get(stagingKey);
            if (usage(tenantPrefix) > maxTenantBytes - bytes.length)
                throw new ObjectMediaBackendException("quota", false, true, null);
            byte[] copy = Arrays.copyOf(bytes, bytes.length);
            content.put(destinationKey, copy);
            ObjectMediaRecord record = new ObjectMediaRecord(destinationKey, copy.length, sha(copy), asset);
            records.put(destinationKey, record);
            return record;
        }
        public void delete(String key) { content.remove(key); records.remove(key); }
        public void close() { }
        int stagedCount() { int count = 0; for (String key : content.keySet()) if (key.contains("/staging/")) count++; return count; }
        private static String sha(byte[] bytes) {
            try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder text = new StringBuilder(); for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 0xff)); return text.toString(); }
            catch (Exception e) { throw new AssertionError(e); }
        }
    }
}
