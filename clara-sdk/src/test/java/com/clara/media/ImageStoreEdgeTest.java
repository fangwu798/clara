package com.clara.media;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Image materialization boundaries: URLs and existing files pass through, while invalid Base64 and
 * oversized payloads fail explicitly.
 */
public class ImageStoreEdgeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void validatesStoreSettings() {
        assertThrows(IllegalArgumentException.class, () -> new ImageStore(null, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageStore(folder.getRoot().toPath(), 0L));
    }

    @Test
    public void emptyAndNonBase64ReferencesPassThrough() throws Exception {
        ImageStore store = new ImageStore(folder.newFolder("images").toPath(), 1024L);
        assertTrue(store.materialize(null).isEmpty());
        assertTrue(store.materialize(Collections.<String>emptyList()).isEmpty());

        Path existing = folder.newFile("existing.png").toPath();
        List<String> result = store.materialize(Arrays.asList("  ", "http://example.com/a.png",
                existing.toString(), "data:text/plain,hello", "short",
                "!".concat(repeat('A', 80))));
        assertEquals(5, result.size());
        assertEquals("http://example.com/a.png", result.get(0));
        assertEquals(existing.toString(), result.get(1));
        assertEquals("data:text/plain,hello", result.get(2));
        assertEquals("short", result.get(3));
    }

    @Test
    public void writesJpegAndWebpByMimeType() throws Exception {
        Path directory = folder.newFolder("typed").toPath();
        ImageStore store = new ImageStore(directory, 1024L);
        String payload = Base64.getEncoder().encodeToString("data".getBytes(StandardCharsets.UTF_8));

        List<String> saved = store.materialize(Arrays.asList(
                "data:image/jpeg;base64," + payload,
                "data:image/webp;charset=utf-8;base64," + payload));

        assertTrue(saved.get(0).endsWith(".jpg"));
        assertTrue(saved.get(1).endsWith(".webp"));
        assertTrue(Files.isRegularFile(java.nio.file.Paths.get(saved.get(0))));
    }

    @Test
    public void invalidBase64AndOversizeFailExplicitly() throws Exception {
        ImageStore store = new ImageStore(folder.newFolder("errors").toPath(), 2L);
        assertThrows(IllegalStateException.class,
                () -> store.materialize(Collections.singletonList("data:image/png;base64,@@@")));
        assertThrows(IllegalStateException.class,
                () -> store.materialize(Collections.singletonList("data:image/png;base64,"
                        + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}))));
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            text.append(value);
        }
        return text.toString();
    }
}
