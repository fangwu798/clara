package com.clara.memory.semantic.milvus;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

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
import static org.junit.Assert.assertTrue;

/**
 * Milvus BM25 全文检索集成联测：连接真实 Milvus（docker compose 部署，默认 127.0.0.1:19530），
 * 验证「建 BM25 集合 → 写入 → 按原文召回」的端到端行为（中文分词、分数压缩、命中排序）。
 *
 * <p>为什么必须有这条链路的真机测试：BM25 集合的字段、函数与索引参数是由客户端拼出来的，
 * 拼错在单测里看不出来（服务端才会拒绝），而它正是「对话记录写进去却召回不到」的根治手段。
 *
 * <p>当 Milvus 不可达时通过 {@link Assume} 自动跳过；可用系统属性覆盖连接：
 * {@code -Dmilvus.host=... -Dmilvus.port=...}。
 */
public class MilvusBm25IntegrationTest {

    private static final String HOST = System.getProperty("milvus.host", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getProperty("milvus.port", "19530"));
    private static final String COLLECTION = "clara_it_bm25";
    private static final String CONVERSATION = "it-bm25-conv";

    @BeforeClass
    public static void requireMilvusReachable() {
        Assume.assumeTrue("Milvus not reachable at " + HOST + ":" + PORT, reachable());
    }

    @Test
    public void writtenConversationIsRecalledByRawText() {
        MilvusCollectionSpec spec = new MilvusCollectionSpec(COLLECTION, MilvusSettings.DEFAULT_ANALYZER);
        MilvusBackend backend = MilvusBackendFactory.create(new MilvusBackendConfig(HOST, PORT), spec);
        MilvusSemanticMemoryStore store = open(backend, spec);
        List<String> ids = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            MemoryEntry pricing = entry("bm25-pricing", "套餐算费特殊规则怎么透传多方案", now);
            MemoryEntry referencing = entry("bm25-referencing",
                    "PackageRuleDescriptionService 被哪些类使用过", now);
            MemoryEntry weather = entry("bm25-weather", "今天下午天气不错，准备出去散步", now);
            ids.add(pricing.getId());
            ids.add(referencing.getId());
            ids.add(weather.getId());
            // BM25 集合不存向量：这里故意传长度为 1 的占位向量，验证入库不再按维度校验
            store.upsert(Arrays.asList(pricing, referencing, weather));
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500));

            List<MemoryMatch> pricingHits = store.search(query("套餐算费特殊规则怎么透传"));
            assertEquals("相关文档应排第一", "bm25-pricing", pricingHits.get(0).getEntry().getId());
            double pricingScore = pricingHits.get(0).getScore();
            assertTrue("压缩后的相似度必须落在 (0,1)，实际 " + pricingScore,
                    pricingScore > 0d && pricingScore < 1d);

            List<MemoryMatch> referencingHits = store.search(query("PackageRuleDescriptionService 被哪些类使用过"));
            assertEquals("相关文档应排第一", "bm25-referencing",
                    referencingHits.get(0).getEntry().getId());
            assertTrue("命中内容应原样回传", referencingHits.get(0).getEntry().getContent()
                    .contains("PackageRuleDescriptionService"));


            assertTrue("不相关文档的分数应明显低于相关文档",
                    scoreOf(referencingHits, "bm25-weather") < scoreOf(referencingHits, "bm25-referencing"));

            store.deleteByConversation(CONVERSATION);
            assertTrue("按会话删除后不应再有本会话条目", awaitConversationCleared(store));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            for (String id : ids) {
                try {
                    store.deleteById(id);
                } catch (RuntimeException ignored) {
                    // 清理失败不影响结论（集合每次运行都会重建）
                }
            }
            store.close();
        }
    }

    /**
     * 等待按会话删除生效：Milvus 的删除写的是 delta 日志，召回侧要等到删除时间戳被保证之后才会过滤掉，
     * 所以刚删完立刻查询可能仍看到条目；这里轮询一小段时间，超时仍能查到才算失败。
     *
     * @param store 待观察的存储，非 null
     * @return 查询不到本会话条目时返回 true
     * @throws InterruptedException 等待被中断时
     */
    private static boolean awaitConversationCleared(MilvusSemanticMemoryStore store)
            throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (store.search(query("套餐算费")).isEmpty()) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(500L));
        }
        return false;
    }

    /**
    /**
     * 打开存储：既有集合结构与 BM25 不匹配时删掉重建（与启动期的 recreateOnSchemaMismatch 同路径）。
     */
    private static MilvusSemanticMemoryStore open(MilvusBackend backend, MilvusCollectionSpec spec) {
        try {
            return new MilvusSemanticMemoryStore(spec, backend);
        } catch (RuntimeException mismatch) {
            backend.dropCollection(spec.getCollection());
            return new MilvusSemanticMemoryStore(spec, backend);
        }
    }

    private static double scoreOf(List<MemoryMatch> matches, String id) {
        for (MemoryMatch match : matches) {
            if (id.equals(match.getEntry().getId())) {
                return match.getScore();
            }
        }
        return -1d;
    }

    private static SemanticMemoryQuery query(String queryText) {
        return SemanticMemoryQuery.builder(new float[] {1f}, 5, -1.0d, System.currentTimeMillis())
                .sourceType(MemorySourceTypeEnum.CONVERSATION)
                .conversationId(CONVERSATION)
                .queryText(queryText)
                .build();
    }

    private static MemoryEntry entry(String id, String content, long now) {
        return MemoryEntry.builder(id, MemorySourceTypeEnum.CONVERSATION, CONVERSATION, content,
                sha256(content), new float[] {1f})
                .conversationId(CONVERSATION)
                .createdAtEpochMillis(now)
                .metadata(Collections.<String, String>emptyMap())
                .build();
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
}
