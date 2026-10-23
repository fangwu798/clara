package com.clara.media;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that local image paths are converted to data URIs while URL / data URI
 * references are left untouched.
 */
public class ImageReferenceNormalizerTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void convertsLocalFileAndKeepsProviderReadyReferences() throws Exception {
        Path image = folder.newFile("ref.png").toPath();
        Files.write(image, "PNGDATA".getBytes(StandardCharsets.UTF_8));
        String dataUri = "data:image/png;base64,AAAA";

        List<String> normalized = ImageReferenceNormalizer.normalize(
                Arrays.asList(image.toString(), "https://cdn.example.com/a.png", dataUri));

        assertEquals(3, normalized.size());
        assertTrue(normalized.get(0), normalized.get(0).startsWith("data:image/png;base64,"));
        assertEquals("https://cdn.example.com/a.png", normalized.get(1));
        assertEquals(dataUri, normalized.get(2));
    }

    @Test
    public void reportsMissingLocalFile() {
        try {
            ImageReferenceNormalizer.normalize(Arrays.asList("missing-reference.png"));
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("参考图不存在"));
            return;
        }
        throw new AssertionError("expected IllegalStateException");
    }
}
