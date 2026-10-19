package com.clara.media;

import com.clara.enums.MediaTypeEnum;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class MediaAssetTest {

    @Test
    public void buildsAssetAndCopiesMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "unit-test");
        MediaAsset asset = MediaAsset.builder("asset-1", MediaTypeEnum.IMAGE, "image/png", 128L)
                .sha256("hash")
                .storageUri("file:///tmp/asset-1.png")
                .ownerTenantId("tenant-1")
                .createdAt(10L)
                .metadata(metadata)
                .build();

        metadata.put("source", "mutated");

        assertEquals("asset-1", asset.getAssetId());
        assertEquals("unit-test", asset.getMetadata().get("source"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingStorageUri() {
        MediaAsset.builder("asset-1", MediaTypeEnum.IMAGE, "image/png", 128L)
                .sha256("hash")
                .ownerTenantId("tenant-1")
                .build();
    }
}
