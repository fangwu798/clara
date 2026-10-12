package com.clara.media.asset;

import com.clara.deployment.local.WindowsSingleHostStoragePolicy;
import com.clara.media.MediaAsset;
import com.clara.media.filesystem.FileSystemMediaStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link MediaAssetIngestor} 的单元测试：把 base64 / data URI 图片引用落成媒体资产，并校验非法输入。
 *
 * <p>夹具使用真实 PNG 字节（由 {@link ImageIO} 生成）与临时目录下的 {@link FileSystemMediaStore}，
 * 因此会真实走一遍媒体校验（魔数、扩展名-MIME 一致性、图片可读性），但不访问网络。
 */
public class MediaAssetIngestorTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private FileSystemMediaStore store;
    private MediaAssetIngestor ingestor;

    @Before
    public void setUp() throws Exception {
        Path root = folder.newFolder("workspace").toPath();
        store = new FileSystemMediaStore(new WindowsSingleHostStoragePolicy(root, "test").mediaStoreConfig());
        ingestor = new MediaAssetIngestor(store, "test");
    }

    @After
    public void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    @Test
    public void ingestsRawBase64Png() throws Exception {
        MediaAsset asset = ingestor.ingest(Base64.getEncoder().encodeToString(pngBytes()));

        assertEquals("image/png", asset.getMimeType());
        assertEquals(64, asset.getAssetId().length());
        assertTrue(asset.getStorageUri().startsWith("clara-media:"));
        assertTrue(asset.getSize() > 0);
    }

    @Test
    public void ingestsDataUriPng() throws Exception {
        MediaAsset asset = ingestor.ingest("data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes()));

        assertEquals("image/png", asset.getMimeType());
    }

    @Test
    public void isIdempotentForSameContent() throws Exception {
        byte[] png = pngBytes();

        MediaAsset first = ingestor.ingest(Base64.getEncoder().encodeToString(png));
        MediaAsset second = ingestor.ingest(Base64.getEncoder().encodeToString(png));

        assertEquals(first.getAssetId(), second.getAssetId());
    }

    @Test
    public void rejectsUnsupportedContent() {
        String notImage = Base64.getEncoder().encodeToString("not an image".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> ingestor.ingest(notImage));
    }

    @Test
    public void rejectsBlankReference() {
        assertThrows(IllegalArgumentException.class, () -> ingestor.ingest("   "));
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
