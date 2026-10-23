package com.clara.media;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for converting Base64 image results into local files.
 */
public class ImageStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void materializesDataUriIntoLocalFile() throws Exception {
        Path directory = folder.newFolder("images").toPath();
        ImageStore store = new ImageStore(directory, 1024);
        byte[] expected = new byte[]{1, 2, 3, 4};
        String reference = "data:image/png;base64," + Base64.getEncoder().encodeToString(expected);

        List<String> result = store.materialize(Arrays.asList(reference));

        assertEquals(1, result.size());
        Path saved = java.nio.file.Paths.get(result.get(0));
        assertTrue(Files.isRegularFile(saved));
        assertArrayEquals(expected, Files.readAllBytes(saved));
    }

    @Test
    public void materializesRawBase64AndKeepsUrls() throws Exception {
        Path directory = folder.newFolder("images").toPath();
        ImageStore store = new ImageStore(directory, 1024);
        String raw = new String(new char[64]).replace('\0', 'A');
        String url = "https://example.com/a.png";

        List<String> result = store.materialize(Arrays.asList(url, raw));

        assertEquals(url, result.get(0));
        assertTrue(Files.isRegularFile(java.nio.file.Paths.get(result.get(1))));
    }
}