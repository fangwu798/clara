package com.clara.memory.semantic.milvus;

import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Milvus 语义记忆存储集成联测：连接真实 Milvus（docker compose 部署，默认 127.0.0.1:19530），
 * 验证 upsert / search / deleteById / deleteByConversation 的端到端行为。
 *
 * <p>当 Milvus 不可达时，本测试通过 {@link Assume} 自动跳过，不影响常规构建；
 * 可用系统属性覆盖连接：{@code -Dmilvus.host=... -Dmilvus.port=...}。
 */
public class MilvusSemanticMemoryStoreIntegrationTest {

    private static final String HOST = System.getProperty("milvus.host", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getProperty("milvus.port", "19530"));
    private static final int DIM = 8;
    private static final String COLLECTION = "clara_it_semantic";

    @BeforeClass
    public static void requireMilvusReachable() {
        Assume.assumeTrue("Milvus not reachable at " + HOST + ":" + PORT, reachable());
    }

    @Test
    public void upsertSearchDeleteRoundTrip() {
        MilvusBackend backend = MilvusBackendFactory.create(new MilvusBackendConfig(HOST, PORT));
        MilvusSemanticMemoryStore store =
                new MilvusSemanticMemoryStore(new MilvusCollectionSpec(COLLECTION, DIM), backend);
        try {
            long now = System.currentTimeMillis();
            MemoryEntry conversationA =
                    entry("it-a", MemorySourceTypeEnum.CONVERSATION, "conv-1", "conv-1", vec(1, 0), now);
            MemoryEntry conversationB =
                    entry("it-b", MemorySourceTypeEnum.CONVERSATION, "conv-1", "conv-1", vec(0, 1), now);
            MemoryEntry prompt = entry("it-c", MemorySourceTypeEnum.PROMPT, "prompt-1", null, vec(1, 0), now);
            store.upsert(Arrays.asList(conversationA, conversationB, prompt));

            List<MemoryMatch> hits = store.search(
                    SemanticMemoryQuery.builder(vec(1, 0), 5, -1.0d, now)
                            .conversationId("conv-1")
                            .build());
            assertFalse("expect conversation hits", hits.isEmpty());
            assertEquals("it-a", hits.get(0).getEntry().getId());

            store.deleteById("it-b");
            List<MemoryMatch> afterDeleteById = store.search(
                    SemanticMemoryQuery.builder(vec(0, 1), 5, -1.0d, now)
                            .conversationId("conv-1")
                            .build());
            for (MemoryMatch match : afterDeleteById) {
                assertNotEquals("it-b", match.getEntry().getId());
            }

            store.deleteByConversation("conv-1");
            List<MemoryMatch> afterDeleteConversation = store.search(
                    SemanticMemoryQuery.builder(vec(1, 0), 5, -1.0d, now)
                            .conversationId("conv-1")
                            .build());
            assertTrue("conversation entries should be removed", afterDeleteConversation.isEmpty());

            List<MemoryMatch> promptHits = store.search(
                    SemanticMemoryQuery.builder(vec(1, 0), 5, -1.0d, now)
                            .sourceType(MemorySourceTypeEnum.PROMPT)
                            .build());
            assertFalse("prompt entry should survive conversation delete", promptHits.isEmpty());
            assertEquals("it-c", promptHits.get(0).getEntry().getId());

            store.deleteById("it-c");
        } finally {
            store.close();
        }
    }

    private static MemoryEntry entry(String id, MemorySourceTypeEnum sourceType, String sourceId,
            String conversationId, float[] vector, long now) {
        return MemoryEntry.builder(id, sourceType, sourceId, "content-" + id,
                sha256("content-" + id), vector)
                .conversationId(conversationId)
                .createdAtEpochMillis(now)
                .metadata(Collections.<String, String>emptyMap())
                .build();
    }

    private static float[] vec(float first, float second) {
        float[] vector = new float[DIM];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                String hex = Integer.toHexString(item & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<MemoryMatch> emptyIfNull(List<MemoryMatch> matches) {
        return matches == null ? new ArrayList<>() : matches;
    }
}
