package com.clara.novel;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.InvocationContext;
import com.clara.media.MediaType;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class NovelCoverAssetBridgeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void storesBase64DecodedImageBytesAsCover() throws Exception {
        Path output = temporary.newFolder("assets").toPath();
        NovelCoverAssetBridge bridge = new NovelCoverAssetBridge(output);

        String assetId = bridge.storeBytes(new byte[] { 4, 5, 6 }, MediaType.IMAGE,
                "image/png", context());

        assertEquals("cover.png", assetId);
        assertArrayEquals(new byte[] { 4, 5, 6 }, Files.readAllBytes(output.resolve("cover.png")));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void rejectsRemoteUrls() throws Exception {
        NovelCoverAssetBridge bridge = new NovelCoverAssetBridge(temporary.newFolder("remote").toPath());
        bridge.storeRemote("https://example.test/cover.png", MediaType.IMAGE,
                "image/png", context());
    }

    private static InvocationContext context() {
        return new InvocationContext("local", "test", "cover", "trace-cover",
                System.currentTimeMillis() + 10000L, new CancellationToken());
    }
}
