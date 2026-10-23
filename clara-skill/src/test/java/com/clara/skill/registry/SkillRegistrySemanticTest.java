package com.clara.skill.registry;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.enums.memory.MemorySourceTypeEnum;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Semantic-memory sync of SkillRegistry: only changed entries are upserted and removed ones are deleted.
 */
public class SkillRegistrySemanticTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void indexesChangedEntriesAndDeletesMissingOnes() throws Exception {
        File root = folder.newFolder("semantic-skill");
        File tools = newFolder(root, "tools");
        File promptDir = newFolder(root, "prompt");
        File toolFile = new File(tools, "lookup.json");
        write(toolFile, tool("lookup", "lookup", "find things", "{\"type\":\"object\"}"));
        File promptFile = new File(promptDir, "greet.json");
        write(promptFile, prompt("greet", "say hi"));

        RecordingStore store = new RecordingStore();
        SkillRegistry registry = new SkillRegistry(null, root, null,
                new Hashing(), store);
        registry.reload();

        assertEquals(2, store.lastUpsert().size());
        MemoryEntry toolEntry = find(store.lastUpsert(), "TOOL_DESCRIPTION:lookup");
        assertNotNull(toolEntry);
        assertEquals("find things", toolEntry.getContent());
        assertEquals("{\"type\":\"object\"}", toolEntry.getMetadata().get(MemoryEntry.TOOL_PARAMETERS_METADATA_KEY));
        assertEquals(3, toolEntry.getVector().length);
        assertNotNull(find(store.lastUpsert(), "PROMPT:greet"));

        store.upserts.clear();
        write(toolFile, tool("lookup", "lookup", "find more things", null));
        registry.reload();
        assertEquals(1, store.upserts.size());
        assertEquals("find more things", find(store.lastUpsert(), "TOOL_DESCRIPTION:lookup").getContent());
        assertTrue(store.lastUpsert().get(0).getMetadata().isEmpty());

        store.upserts.clear();
        registry.reload();
        assertTrue("unchanged sources must not be re-indexed", store.upserts.isEmpty());

        assertTrue(promptFile.delete());
        registry.findPrompt("greet");
        assertEquals(Collections.singletonList("PROMPT:greet"), store.deleted);
    }

    @Test
    public void usesPromptIdWhenDescriptionIsBlank() throws Exception {
        File root = folder.newFolder("semantic-blank");
        File promptDir = newFolder(root, "prompt");
        write(new File(promptDir, "blank.json"), "{\"id\":\"blank\",\"segments\":[{\"role\":\"system\",\"content\":\"x\"}]}");

        RecordingStore store = new RecordingStore();
        SkillRegistry registry = new SkillRegistry(null, root, null, new Hashing(), store);
        registry.reload();

        assertEquals("blank", find(store.lastUpsert(), "PROMPT:blank").getContent());
    }

    private static MemoryEntry find(List<MemoryEntry> entries, String id) {
        for (MemoryEntry entry : entries) {
            if (id.equals(entry.getId())) {
                return entry;
            }
        }
        return null;
    }

    private static String tool(String id, String name, String description, String parameters) {
        StringBuilder json = new StringBuilder();
        json.append("{\"id\":\"").append(id).append("\",\"name\":\"").append(name)
                .append("\",\"type\":\"BUILTIN\",\"description\":\"").append(description)
                .append("\",\"script\":\"").append(id).append("\"");
        if (parameters != null) {
            json.append(",\"parameters\":").append(parameters);
        }
        return json.append('}').toString();
    }

    private static String prompt(String id, String description) {
        return "{\"id\":\"" + id + "\",\"description\":\"" + description + "\",\"segments\":[{\"role\":\"system\",\"content\":\"x\"}]}";
    }

    private static File newFolder(File root, String name) throws IOException {
        File dir = new File(root, name);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        return dir;
    }

    private static void write(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Hashing implements IEmbeddingClient {
        @Override
        public float[] embed(String text) {
            return new float[]{(text == null ? 0 : text.length()), 1.0f, 2.0f};
        }
    }

    private static final class RecordingStore implements ISemanticMemoryStore {
        private final List<List<MemoryEntry>> upserts = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public void upsert(List<MemoryEntry> entries) {
            upserts.add(entries);
        }

        @Override
        public List<MemoryMatch> search(SemanticMemoryQuery query) {
            return Collections.emptyList();
        }

        @Override
        public void deleteById(String entryId) {
            deleted.add(entryId);
        }

        @Override
        public void deleteByConversation(String conversationId) {
        }

        @Override
        public void close() {
        }

        private List<MemoryEntry> lastUpsert() {
            return upserts.isEmpty() ? Collections.<MemoryEntry>emptyList() : upserts.get(upserts.size() - 1);
        }
    }
}