package com.clara.memory.semantic.local;

import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ErrorCodes;
import com.clara.memory.semantic.api.*;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class LocalSemanticMemoryStoreTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();
    private Path root;

    @Before
    public void setUp() throws Exception {
        root = folder.newFolder("workspace").toPath().toRealPath();
    }

    @Test
    public void filtersTenantMetadataExpiryAndOrdersByCosine() throws Exception {
        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(config(root, 20, 100, SemanticMemoryEviction.REJECT_NEW));
        long now = System.currentTimeMillis();
        store.upsert(Arrays.asList(entry("best", "tenant", "c1", new float[]{1, 0}, now, 0, "blue"), entry("other-tenant", "other", "c1", new float[]{1, 0}, now + 1, 0, "blue"), entry("second", "tenant", "c1", new float[]{0.8f, 0.2f}, now + 2, 0, "blue"), entry("expired", "tenant", "c1", new float[]{1, 0}, now, now + 10, "blue"), entry("wrong-tag", "tenant", "c1", new float[]{1, 0}, now + 3, 0, "red")));
        SemanticMemoryQuery query = new SemanticMemoryQuery("tenant", null, null, "general", new float[]{1, 0}, 10, 0.5, now + 20, Collections.singletonMap("tag", "blue"));
        List<MemoryMatch> found = store.search(query);
        assertEquals(2, found.size());
        assertEquals("best", found.get(0).getEntry().getId());
        assertEquals("second", found.get(1).getEntry().getId());
        store.close();
    }

    @Test
    public void snapshotsAndRestoresDeletes() throws Exception {
        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(config(root, 10, 1, SemanticMemoryEviction.REJECT_NEW));
        store.upsert(Collections.singletonList(entry("one", "tenant", "conversation", new float[]{1, 0}, 1, 0, "blue")));
        store.close();
        Files.write(root.resolve(".clara/memory/snapshot.bin"), new byte[]{1}, StandardOpenOption.TRUNCATE_EXISTING);
        LocalSemanticMemoryStore reopened = new LocalSemanticMemoryStore(config(root, 10, 1, SemanticMemoryEviction.REJECT_NEW));
        assertEquals(1, search(reopened, "tenant").size());
        reopened.deleteByConversation("tenant", "conversation");
        reopened.close();
        LocalSemanticMemoryStore empty = new LocalSemanticMemoryStore(config(root, 10, 1, SemanticMemoryEviction.REJECT_NEW));
        assertTrue(search(empty, "tenant").isEmpty());
        empty.close();
    }

    @Test
    public void replaysWalAndDiscardsOnlyPartialTail() throws Exception {
        LocalSemanticMemoryStore source = new LocalSemanticMemoryStore(config(root, 10, 100, SemanticMemoryEviction.REJECT_NEW));
        source.upsert(Collections.singletonList(entry("one", "tenant", "c", new float[]{1, 0}, 1, 0, "blue")));
        Path copy = folder.newFolder("copy").toPath().toRealPath();
        copyWal(root, copy);
        LocalSemanticMemoryStore replayed = new LocalSemanticMemoryStore(config(copy, 10, 100, SemanticMemoryEviction.REJECT_NEW));
        assertEquals(1, search(replayed, "tenant").size());
        replayed.close();
        Path partial = folder.newFolder("partial").toPath().toRealPath();
        copyWal(root, partial);
        Path wal = firstWal(partial);
        byte[] bytes = Files.readAllBytes(wal);
        Files.write(wal, Arrays.copyOf(bytes, 5), StandardOpenOption.TRUNCATE_EXISTING);
        LocalSemanticMemoryStore recovered = new LocalSemanticMemoryStore(config(partial, 10, 100, SemanticMemoryEviction.REJECT_NEW));
        assertTrue(search(recovered, "tenant").isEmpty());
        recovered.close();
        source.close();
    }

    @Test
    public void rejectsChecksumDamageConfigMismatchAndSecondWriter() throws Exception {
        LocalSemanticMemoryStore source = new LocalSemanticMemoryStore(config(root, 10, 100, SemanticMemoryEviction.REJECT_NEW));
        assertCode(ErrorCodes.SEMANTIC_MEMORY_LOCKED, new Runnable() {
            public void run() {
                new LocalSemanticMemoryStore(config(root, 10, 100, SemanticMemoryEviction.REJECT_NEW));
            }
        });
        source.upsert(Collections.singletonList(entry("one", "tenant", "c", new float[]{1, 0}, 1, 0, "blue")));
        Path damaged = folder.newFolder("damaged").toPath().toRealPath();
        copyWal(root, damaged);
        Path wal = firstWal(damaged);
        byte[] bytes = Files.readAllBytes(wal);
        bytes[bytes.length - 1] ^= 1;
        Files.write(wal, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        assertCode(ErrorCodes.SEMANTIC_MEMORY_CORRUPTED, new Runnable() {
            public void run() {
                new LocalSemanticMemoryStore(config(damaged, 10, 100, SemanticMemoryEviction.REJECT_NEW));
            }
        });
        source.close();
        assertCode(ErrorCodes.SEMANTIC_MEMORY_CONFIG_MISMATCH, new Runnable() {
            public void run() {
                new LocalSemanticMemoryStore(new SemanticMemoryStoreConfig(root, "workspace", "different", "v1", 2, 10, 100, 1000, SemanticMemoryDurability.FSYNC_EACH, SemanticMemoryEviction.REJECT_NEW));
            }
        });
    }

    @Test
    public void evictsOldestWithoutEvictingIncomingReplacement() throws Exception {
        LocalSemanticMemoryStore store = new LocalSemanticMemoryStore(config(root, 2, 100, SemanticMemoryEviction.OLDEST_FIRST));
        store.upsert(Arrays.asList(entry("a", "tenant", "c", new float[]{1, 0}, 1, 0, "blue"), entry("b", "tenant", "c", new float[]{0, 1}, 2, 0, "blue")));
        store.upsert(Arrays.asList(entry("a", "tenant", "c", new float[]{0.9f, 0.1f}, 3, 0, "blue"), entry("c", "tenant", "c", new float[]{1, 0}, 4, 0, "blue")));
        List<MemoryMatch> found = search(store, "tenant");
        Set<String> ids = new HashSet<String>();
        for (MemoryMatch match : found) ids.add(match.getEntry().getId());
        assertEquals(new HashSet<String>(Arrays.asList("a", "c")), ids);
        store.close();
    }

    private static SemanticMemoryStoreConfig config(Path root, int max, int snapshot, SemanticMemoryEviction eviction) {
        return new SemanticMemoryStoreConfig(root, "workspace", "model", "v1", 2, max, snapshot, 1000, SemanticMemoryDurability.FSYNC_EACH, eviction);
    }

    private static MemoryEntry entry(String id, String tenant, String conversation, float[] vector, long created, long expires, String tag) {
        return new MemoryEntry(id, tenant, "user", conversation, "general", MemorySourceType.CONVERSATION, conversation, id, sha(id), vector, created, expires, Collections.singletonMap("tag", tag));
    }

    private static List<MemoryMatch> search(LocalSemanticMemoryStore store, String tenant) {
        return store.search(new SemanticMemoryQuery(tenant, null, null, null, new float[]{1, 0}, 20, -1, Long.MAX_VALUE - 1, Collections.<String, String>emptyMap()));
    }

    private static void copyWal(Path from, Path to) throws IOException {
        Path target = to.resolve(".clara/memory/wal");
        Files.createDirectories(target);
        DirectoryStream<Path> stream = Files.newDirectoryStream(from.resolve(".clara/memory/wal"), "*.wal");
        try {
            for (Path file : stream) Files.copy(file, target.resolve(file.getFileName()));
        } finally {
            stream.close();
        }
    }

    private static Path firstWal(Path root) throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(root.resolve(".clara/memory/wal"), "*.wal");
        try {
            return stream.iterator().next();
        } finally {
            stream.close();
        }
    }

    private static String sha(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertCode(String code, Runnable action) {
        try {
            action.run();
            fail("expected " + code);
        } catch (
                ClaraException e) {
            assertEquals(code, e.getError().getCode());
        }
    }
}
