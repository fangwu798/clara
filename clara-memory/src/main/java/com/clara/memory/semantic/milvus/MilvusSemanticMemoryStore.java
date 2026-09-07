package com.clara.memory.semantic.milvus;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.memory.semantic.api.MemoryEntry;
import com.clara.memory.semantic.api.MemoryMatch;
import com.clara.memory.semantic.api.SemanticMemoryQuery;
import com.clara.memory.semantic.api.SemanticMemoryStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将语义记忆映射到 Milvus 后端，并统一执行集合初始化、租户过滤、结果校验和过期剔除。
 *
 * <p>常规用法：通过 {@link MilvusSemanticMemoryConfig} 和已连接的 {@link MilvusBackend} 构造实例，
 * 使用 {@link #upsert}、{@link #search} 和 {@link #deleteByConversation}，结束后调用 {@link #close()}。
 *
 * <p>注意：集合规格和向量维度必须与配置一致。后端查询必须保留本类生成的租户及业务过滤条件；本类还会
 * 对返回记录再次校验并过滤过期数据。实例关闭时会关闭所注入的后端，调用方不要重复共享其生命周期。
 */
public final class MilvusSemanticMemoryStore implements SemanticMemoryStore {
    private final MilvusSemanticMemoryConfig config;
    private final MilvusBackend backend;
    private boolean closed;

    public MilvusSemanticMemoryStore(MilvusSemanticMemoryConfig config, MilvusBackend backend) {
        if (config == null || backend == null) { throw new IllegalArgumentException(); }
        this.config = config;
        this.backend = backend;
        MilvusCollectionSpec expected = new MilvusCollectionSpec(config.getCollection(),
                config.getEmbeddingModel(), config.getEmbeddingRevision(),
                config.getDimension(), "COSINE");
        try {
            if (!expected.sameIdentity(backend.ensureCollection(expected))) mismatch();
        } catch (RuntimeException failure) {
            closeAfterInitializationFailure(backend, failure);
            if (failure instanceof MilvusBackendException) {
                backend((MilvusBackendException) failure);
            }
            if (failure instanceof ClaraException) { throw failure; }
            backend(false);
        }
    }

    public synchronized void upsert(List<MemoryEntry> entries) {
        open();
        if (entries == null || entries.isEmpty() || entries.contains(null)) {
            throw new IllegalArgumentException("entries are required");
        }
        Set<String> ids = new HashSet<String>();
        for (MemoryEntry entry : entries) {
            dimension(entry.getDimension());
            if (!ids.add(entry.getId())) { throw new IllegalArgumentException("duplicate memory id"); }
        }
        try {
            for (int from = 0; from < entries.size(); from += config.getMaxBatchSize()) {
                int to = Math.min(entries.size(), from + config.getMaxBatchSize());
                backend.upsert(config.getCollection(), Collections.unmodifiableList(
                        new ArrayList<MemoryEntry>(entries.subList(from, to))));
            }
        } catch (MilvusBackendException failure) {
            backend(failure);
        } catch (RuntimeException failure) {
            backend(false);
        }
    }

    public synchronized List<MemoryMatch> search(SemanticMemoryQuery query) {
        open();
        if (query == null) { throw new IllegalArgumentException(); }
        dimension(query.getVector().length);
        if (query.getTopK() > config.getMaxTopK()) limit();
        long multiplied = (long) query.getTopK() * config.getOversamplingFactor();
        int candidates = (int) Math.min(config.getMaxTopK(),
                Math.min(Integer.MAX_VALUE, multiplied));
        List<MemoryEntry> raw;
        try {
            raw = backend.search(config.getCollection(), new MilvusBackendQuery(query, candidates));
        } catch (MilvusBackendException failure) {
            backend(failure);
            return Collections.emptyList();
        } catch (RuntimeException failure) {
            backend(false);
            return Collections.emptyList();
        }
        if (raw == null) backend(false);
        if (raw.size() > candidates) limit();

        Map<String, MemoryMatch> matches = new LinkedHashMap<String, MemoryMatch>();
        for (MemoryEntry entry : raw) {
            if (!matches(query, entry)) continue;
            double score = cosine(query.getVector(), entry.getVector());
            if (score < query.getMinScore()) continue;
            MemoryMatch current = matches.get(entry.getId());
            if (current == null || score > current.getScore()) {
                matches.put(entry.getId(), new MemoryMatch(entry, score));
            }
        }
        List<MemoryMatch> result = new ArrayList<MemoryMatch>(matches.values());
        Collections.sort(result, new Comparator<MemoryMatch>() {
            public int compare(MemoryMatch left, MemoryMatch right) {
                int score = Double.compare(right.getScore(), left.getScore());
                if (score != 0) return score;
                int time = Long.compare(right.getEntry().getCreatedAtEpochMillis(),
                        left.getEntry().getCreatedAtEpochMillis());
                return time != 0 ? time
                        : left.getEntry().getId().compareTo(right.getEntry().getId());
            }
        });
        if (result.size() > query.getTopK()) {
            result = new ArrayList<MemoryMatch>(result.subList(0, query.getTopK()));
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized void deleteByConversation(String tenant, String conversation) {
        open();
        if (isBlank(tenant) || isBlank(conversation)) { throw new IllegalArgumentException(); }
        try {
            backend.deleteByConversation(config.getCollection(), tenant, conversation);
        } catch (MilvusBackendException failure) {
            backend(failure);
        } catch (RuntimeException failure) {
            backend(false);
        }
    }

    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        backend.close();
    }

    private boolean matches(SemanticMemoryQuery query, MemoryEntry entry) {
        return entry != null
                && entry.getDimension() == config.getDimension()
                && !entry.isExpired(query.getNowEpochMillis())
                && entry.getTenantId().equals(query.getTenantId())
                && (query.getUserId() == null || query.getUserId().equals(entry.getUserId()))
                && (query.getConversationId() == null
                        || query.getConversationId().equals(entry.getConversationId()))
                && (query.getDomain() == null || query.getDomain().equals(entry.getDomain()))
                && entry.getMetadata().entrySet().containsAll(
                        query.getMetadataFilters().entrySet());
    }

    private void dimension(int value) {
        if (value != config.getDimension()) mismatch();
    }

    private void open() {
        if (closed) { throw new IllegalStateException("semantic memory store is closed"); }
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        return Math.max(-1, Math.min(1,
                dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
    }

    private static void closeAfterInitializationFailure(MilvusBackend backend,
            RuntimeException failure) {
        try {
            backend.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void mismatch() {
        throw error(ErrorCodes.SEMANTIC_MEMORY_CONFIG_MISMATCH,
                ErrorCategory.CONFIGURATION, false);
    }

    private static void limit() {
        throw error(ErrorCodes.SEMANTIC_MEMORY_RESOURCE_LIMIT,
                ErrorCategory.RESOURCE_LIMIT, false);
    }

    private static void backend(MilvusBackendException failure) {
        throw error(ErrorCodes.SEMANTIC_MEMORY_BACKEND_FAILED,
                ErrorCategory.EXTERNAL_SERVICE, failure.isRetryable());
    }

    private static void backend(boolean retryable) {
        throw error(ErrorCodes.SEMANTIC_MEMORY_BACKEND_FAILED,
                ErrorCategory.EXTERNAL_SERVICE, retryable);
    }

    private static ClaraException error(String code, ErrorCategory category,
            boolean retryable) {
        return new ClaraException(new ClaraError(code, category, code, retryable,
                null, null, null, null, Collections.<String, String>emptyMap()));
    }
}
