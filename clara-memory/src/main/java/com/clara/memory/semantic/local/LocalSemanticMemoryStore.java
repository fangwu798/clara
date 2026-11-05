package com.clara.memory.semantic.local;

import com.clara.core.diagnostics.Diagnostics;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.MemoryMatch;
import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.clara.memory.semantic.SemanticMemoryErrors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地语义记忆存储：进程内保存记忆向量，检索采用暴力扫描 + 余弦相似度，无外部向量库依赖。
 *
 * <p>嵌入模型需全程一致（同维度），构造时声明向量维度，入库与检索都校验维度。
 * {@code deleteByConversation} 仅删除 {@code CONVERSATION} 来源条目。
 *
 * <p>本地持久化：传入工作目录时，对话记录会额外写入
 * {@code .clara/state/semantic/<会话哈希>.jsonl}，重启后自动恢复。这样未安装 Milvus 时，
 * 历史消息仍然可以跨进程召回，而不是只活在当前进程里。其余来源（提示词 / 工具描述）每次启动都会
 * 由技能注册表重新写入，故不落盘。
 *
 * <p>文本检索：传入 {@code queryText} 且条目来源为对话时，用轻量词项重合度打分，避免离线占位嵌入
 * 分数过低导致「历史写进去了但永远召不回来」。向量检索仍用于提示词等非对话条目。
 */
public final class LocalSemanticMemoryStore implements ISemanticMemoryStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 单会话最多保留的记忆条目数，防止长期使用后内存与文件无限增长。
     */
    private static final int MAX_ENTRIES_PER_CONVERSATION = 2000;

    /**
     * 中文单字切分时忽略的高频虚词，降低「的了是在」这类字造成的误召回。
     */
    private static final String CJK_STOP_CHARS = "的了是在和与或及就都而也不没有我你他她它这那";

    private final int dimension;
    private final Path storageDirectory;
    private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * 创建不落盘的本地语义记忆存储。
     *
     * @param dimension 向量维度，必须为正
     * @throws IllegalArgumentException dimension 非正时
     */
    public LocalSemanticMemoryStore(int dimension) {
        this(dimension, null);
    }

    /**
     * 创建本地语义记忆存储。
     *
     * @param dimension     向量维度，必须为正
     * @param baseDirectory 工作目录；为 null 时不做持久化
     * @throws IllegalArgumentException dimension 非正时
     * @throws IllegalStateException    持久化目录无法创建时
     */
    public LocalSemanticMemoryStore(int dimension, Path baseDirectory) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        this.dimension = dimension;
        if (baseDirectory == null) {
            this.storageDirectory = null;
            return;
        }
        Path directory = baseDirectory.resolve(".clara").resolve("state").resolve("semantic");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create semantic memory directory: " + directory, e);
        }
        this.storageDirectory = directory;
        loadPersistedEntries();
    }

    @Override
    public synchronized void upsert(List<MemoryEntry> values) {
        open();
        if (values == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        if (values.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        Set<String> existingIds = new HashSet<>();
        for (MemoryEntry entry : values) {
            if (entry == null) {
                throw new IllegalArgumentException("entries must not contain null");
            }
            if (entry.getDimension() != dimension) {
                throw SemanticMemoryErrors.configMismatch();
            }
            if (!ids.add(entry.getId())) {
                throw new IllegalArgumentException("duplicate memory id: " + entry.getId());
            }
            if (entries.containsKey(entry.getId())) {
                existingIds.add(entry.getId());
            }
        }
        for (MemoryEntry entry : values) {
            entries.put(entry.getId(), entry);
        }
        persistUpsert(values, existingIds);
    }

    @Override
    public synchronized List<MemoryMatch> search(SemanticMemoryQuery query) {
        open();
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (query.getVector().length != dimension) {
            throw SemanticMemoryErrors.configMismatch();
        }
        List<MemoryMatch> matches = new ArrayList<>();
        for (MemoryEntry entry : entries.values()) {
            if (!matches(query, entry)) {
                continue;
            }
            double score = relevance(query, entry);
            if (score >= query.getMinScore()) {
                matches.add(new MemoryMatch(entry, score));
            }
        }
        matches.sort((left, right) -> {
            int byScore = Double.compare(right.getScore(), left.getScore());
            if (byScore != 0) {
                return byScore;
            }
            int byTime = Long.compare(right.getEntry().getCreatedAtEpochMillis(),
                    left.getEntry().getCreatedAtEpochMillis());
            if (byTime != 0) {
                return byTime;
            }
            return left.getEntry().getId().compareTo(right.getEntry().getId());
        });
        if (matches.size() > query.getTopK()) {
            matches = new ArrayList<>(matches.subList(0, query.getTopK()));
        }
        return Collections.unmodifiableList(matches);
    }

    @Override
    public synchronized void deleteById(String entryId) {
        open();
        if (entryId == null) {
            throw new IllegalArgumentException("entryId must not be null");
        }
        MemoryEntry removed = entries.remove(entryId);
        if (removed != null && isConversationEntry(removed)) {
            rewriteConversation(removed.getConversationId());
        }
    }

    @Override
    public synchronized void deleteByConversation(String conversationId) {
        open();
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        Iterator<Map.Entry<String, MemoryEntry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            MemoryEntry entry = iterator.next().getValue();
            if (entry.getSourceType() == MemorySourceTypeEnum.CONVERSATION
                    && conversationId.equals(entry.getConversationId())) {
                iterator.remove();
            }
        }
        deleteConversationFile(conversationId);
    }

    @Override
    public synchronized void close() {
        closed = true;
        entries.clear();
    }

    /**
     * 是否启用了本地文件持久化。
     *
     * @return 构造时传入工作目录时为 true
     */
    public boolean isPersistent() {
        return storageDirectory != null;
    }

    /**
     * 对话条目优先用文本重合度：离线占位嵌入的余弦分过低，直接用它会让本地召回形同虚设。
     */
    private static double relevance(SemanticMemoryQuery query, MemoryEntry entry) {
        String queryText = query.getQueryText();
        if (queryText != null && !queryText.trim().isEmpty()
                && entry.getSourceType() == MemorySourceTypeEnum.CONVERSATION) {
            return lexicalScore(queryText, entry.getContent());
        }
        return cosine(query.getVector(), entry.getVector());
    }

    /**
     * 轻量词项重合度：按词项权重折算到 (0, 1)，口径接近 Milvus BM25 的 score / (score + 1)。
     */
    private static double lexicalScore(String queryText, String content) {
        Map<String, Integer> queryTokens = tokens(queryText);
        if (queryTokens.isEmpty()) {
            return -1.0d;
        }
        Set<String> contentTokens = tokens(content).keySet();
        int raw = 0;
        for (Map.Entry<String, Integer> token : queryTokens.entrySet()) {
            if (contentTokens.contains(token.getKey())) {
                raw += token.getValue();
            }
        }
        return raw <= 0 ? 0.0d : (double) raw / (raw + 1.0d);
    }

    private boolean matches(SemanticMemoryQuery query, MemoryEntry entry) {
        if (entry == null || entry.isExpired(query.getNowEpochMillis())) {
            return false;
        }
        if (query.getSourceType() != null && query.getSourceType() != entry.getSourceType()) {
            return false;
        }
        if (query.getConversationId() != null
                && !query.getConversationId().equals(entry.getConversationId())) {
            return false;
        }
        Map<String, String> filters = query.getFilters();
        if (filters != null && !filters.isEmpty()) {
            for (Map.Entry<String, String> filter : filters.entrySet()) {
                String value = entry.getMetadata().get(filter.getKey());
                if (!filter.getValue().equals(value)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 切分词项并给出权重：英文单词 3、中文双字 2、中文单字 1。
     */
    private static Map<String, Integer> tokens(String text) {
        Map<String, Integer> tokens = new LinkedHashMap<>();
        if (text == null || text.trim().isEmpty()) {
            return tokens;
        }
        String value = text.toLowerCase(Locale.ROOT);
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (isCjk(current)) {
                int start = index;
                while (index < value.length() && isCjk(value.charAt(index))) {
                    index++;
                }
                String run = value.substring(start, index);
                for (int offset = 0; offset < run.length(); offset++) {
                    char single = run.charAt(offset);
                    if (CJK_STOP_CHARS.indexOf(single) < 0) {
                        addToken(tokens, String.valueOf(single), 1);
                    }
                }
                for (int offset = 0; offset + 1 < run.length(); offset++) {
                    addToken(tokens, run.substring(offset, offset + 2), 2);
                }
            } else if (Character.isLetterOrDigit(current)) {
                int start = index;
                while (index < value.length() && Character.isLetterOrDigit(value.charAt(index))) {
                    index++;
                }
                String word = value.substring(start, index);
                addToken(tokens, word, word.length() >= 2 ? 3 : 1);
            } else {
                index++;
            }
        }
        return tokens;
    }

    private static void addToken(Map<String, Integer> tokens, String token, int weight) {
        if (token.isEmpty()) {
            return;
        }
        Integer existing = tokens.get(token);
        if (existing == null || existing < weight) {
            tokens.put(token, weight);
        }
    }

    private static boolean isCjk(char value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }

    private void open() {
        if (closed) {
            throw new IllegalStateException("semantic memory store is closed");
        }
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
        if (leftNorm == 0 || rightNorm == 0) {
            return -1;
        }
        return Math.max(-1, Math.min(1, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
    }

    // ---- persistence -------------------------------------------------------

    private void loadPersistedEntries() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDirectory, "*.jsonl")) {
            for (Path file : stream) {
                loadFile(file);
            }
        } catch (IOException e) {
            Diagnostics.warn("[memory] 本地历史缓存目录读取失败，本次仅使用进程内记忆：" + e.getMessage());
        }
    }

    private void loadFile(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Diagnostics.warn("[memory] 本地历史缓存文件读取失败，已跳过：" + file.getFileName());
            return;
        }
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            try {
                MemoryEntry entry = decodeEntry(line);
                if (entry != null && isConversationEntry(entry) && entry.getDimension() == dimension) {
                    entries.put(entry.getId(), entry);
                }
            } catch (RuntimeException ignored) {
                // 单行损坏不影响其它历史记录
            }
        }
    }

    private void persistUpsert(List<MemoryEntry> values, Set<String> existingIds) {
        if (storageDirectory == null) {
            return;
        }
        Map<String, Boolean> conversations = new LinkedHashMap<>();
        Map<String, List<MemoryEntry>> appended = new LinkedHashMap<>();
        for (MemoryEntry entry : values) {
            if (!isConversationEntry(entry)) {
                continue;
            }
            String conversationId = entry.getConversationId();
            boolean rewrite = existingIds.contains(entry.getId());
            Boolean previous = conversations.get(conversationId);
            conversations.put(conversationId, rewrite || Boolean.TRUE.equals(previous));
            if (!appended.containsKey(conversationId)) {
                appended.put(conversationId, new ArrayList<MemoryEntry>());
            }
            appended.get(conversationId).add(entry);
        }
        try {
            for (Map.Entry<String, Boolean> conversation : conversations.entrySet()) {
                if (Boolean.TRUE.equals(conversation.getValue())) {
                    rewriteConversation(conversation.getKey());
                } else {
                    appendConversation(conversation.getKey(), appended.get(conversation.getKey()));
                }
            }
        } catch (IOException e) {
            Diagnostics.warn("[memory] 本地历史缓存写入失败（本轮继续，内存中仍可用）：" + e.getMessage());
        }
    }

    private void appendConversation(String conversationId, List<MemoryEntry> values) throws IOException {
        if (values == null || values.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (MemoryEntry entry : values) {
            text.append(encodeEntry(entry)).append('\n');
        }
        Path file = fileFor(conversationId);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void rewriteConversation(String conversationId) {
        if (storageDirectory == null) {
            return;
        }
        List<MemoryEntry> conversationEntries = new ArrayList<>();
        for (MemoryEntry entry : entries.values()) {
            if (isConversationEntry(entry) && conversationId.equals(entry.getConversationId())) {
                conversationEntries.add(entry);
            }
        }
        conversationEntries.sort((left, right) -> {
            int byTime = Long.compare(left.getCreatedAtEpochMillis(), right.getCreatedAtEpochMillis());
            return byTime != 0 ? byTime : left.getId().compareTo(right.getId());
        });
        if (conversationEntries.size() > MAX_ENTRIES_PER_CONVERSATION) {
            conversationEntries = new ArrayList<>(conversationEntries.subList(
                    conversationEntries.size() - MAX_ENTRIES_PER_CONVERSATION, conversationEntries.size()));
        }
        try {
            if (conversationEntries.isEmpty()) {
                Files.deleteIfExists(fileFor(conversationId));
                return;
            }
            StringBuilder text = new StringBuilder();
            for (MemoryEntry entry : conversationEntries) {
                text.append(encodeEntry(entry)).append('\n');
            }
            writeAtomic(fileFor(conversationId), text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Diagnostics.warn("[memory] 本地历史缓存重写失败（内存中仍可用）：" + e.getMessage());
        }
    }

    private void deleteConversationFile(String conversationId) {
        if (storageDirectory == null) {
            return;
        }
        try {
            Files.deleteIfExists(fileFor(conversationId));
        } catch (IOException e) {
            Diagnostics.warn("[memory] 本地历史缓存删除失败：" + e.getMessage());
        }
    }

    private void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private Path fileFor(String conversationId) {
        return storageDirectory.resolve(sha256(conversationId) + ".jsonl");
    }

    private static boolean isConversationEntry(MemoryEntry entry) {
        return entry != null && entry.getSourceType() == MemorySourceTypeEnum.CONVERSATION
                && entry.getConversationId() != null && !entry.getConversationId().trim().isEmpty();
    }

    private static String encodeEntry(MemoryEntry entry) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", entry.getId());
        node.put("sourceType", entry.getSourceType().name());
        node.put("sourceId", entry.getSourceId());
        node.put("conversationId", entry.getConversationId());
        node.put("content", entry.getContent());
        node.put("contentHash", entry.getContentHash());
        ArrayNode vector = node.putArray("vector");
        for (float value : entry.getVector()) {
            vector.add(value);
        }
        node.put("createdAtEpochMillis", entry.getCreatedAtEpochMillis());
        node.put("expiresAtEpochMillis", entry.getExpiresAtEpochMillis());
        ObjectNode metadata = node.putObject("metadata");
        for (Map.Entry<String, String> item : entry.getMetadata().entrySet()) {
            metadata.put(item.getKey(), item.getValue());
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException("cannot encode memory entry", e);
        }
    }

    private static MemoryEntry decodeEntry(String line) {
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (IOException e) {
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode vectorNode = node.get("vector");
        if (vectorNode == null || !vectorNode.isArray() || vectorNode.size() == 0) {
            return null;
        }
        float[] vector = new float[vectorNode.size()];
        for (int index = 0; index < vectorNode.size(); index++) {
            vector[index] = (float) vectorNode.get(index).asDouble();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        JsonNode metadataNode = node.get("metadata");
        if (metadataNode != null && metadataNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = metadataNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                metadata.put(field.getKey(), field.getValue().asText());
            }
        }
        String sourceType = text(node, "sourceType");
        if (sourceType == null) {
            return null;
        }
        return MemoryEntry.builder(text(node, "id"), MemorySourceTypeEnum.valueOf(sourceType),
                        text(node, "sourceId"), text(node, "content"), text(node, "contentHash"), vector)
                .conversationId(text(node, "conversationId"))
                .createdAtEpochMillis(node.path("createdAtEpochMillis").asLong())
                .expiresAtEpochMillis(node.path("expiresAtEpochMillis").asLong())
                .metadata(metadata.isEmpty() ? null : metadata)
                .build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
