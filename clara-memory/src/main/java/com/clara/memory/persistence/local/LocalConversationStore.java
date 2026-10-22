package com.clara.memory.persistence.local;

import com.clara.core.message.IContent;
import com.clara.enums.RoleEnum;
import com.clara.enums.interaction.HistoryClearScopeEnum;
import com.clara.memory.persistence.ConversationCodec;
import com.clara.memory.persistence.ConversationKey;
import com.clara.memory.persistence.ConversationSnapshot;
import com.clara.memory.persistence.ConversationStore;
import com.clara.memory.persistence.ConversationStoreErrors;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 本地文件会话存储：把会话历史写入 {@code .clara/state/conversations}，采用目录级排他锁与原子写。
 *
 * <p>构造只创建目录并取锁、不预加载历史；重启后按会话键读取对应文件恢复。会话文件命名为
 * {@code sha256(conversationId + 供应商 + 模型).bin}，写入走临时文件 + {@code force} + 原子替换，
 * 避免半写文件；单会话文件大小上限默认 128MB。
 */
public final class LocalConversationStore implements ConversationStore {

    /**
     * 单会话文件大小默认上限：128MB。
     */
    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 128L * 1024 * 1024;

    private static final String LOCK_FILE = "store.lock";

    private final Path directory;
    private final long maxFileSizeBytes;
    private final ConversationCodec codec = new ConversationCodec();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private volatile boolean closed = false;

    /**
     * 创建本地会话存储，单会话文件大小上限取默认值。
     *
     * @param baseDirectory 工作目录，会话目录创建在其下的 {@code .clara/state/conversations}
     */
    public LocalConversationStore(Path baseDirectory) {
        this(baseDirectory, DEFAULT_MAX_FILE_SIZE_BYTES);
    }

    /**
     * 创建本地会话存储。
     *
     * @param baseDirectory   工作目录，不可为 null
     * @param maxFileSizeBytes 单会话文件大小上限，必须为正
     * @throws IllegalArgumentException baseDirectory 为 null 或 maxFileSizeBytes 非正时
     */
    public LocalConversationStore(Path baseDirectory, long maxFileSizeBytes) {
        if (baseDirectory == null) {
            throw new IllegalArgumentException("baseDirectory must not be null");
        }
        if (maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("maxFileSizeBytes must be positive");
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
        Path conversations = baseDirectory.resolve(".clara").resolve("state").resolve("conversations");
        FileChannel channel = null;
        FileLock acquired;
        try {
            Files.createDirectories(conversations);
            channel = FileChannel.open(conversations.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            acquired = channel.tryLock();
            if (acquired == null) {
                closeQuietly(channel);
                throw ConversationStoreErrors.locked();
            }
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            throw ConversationStoreErrors.locked();
        } catch (IOException e) {
            closeQuietly(channel);
            throw ConversationStoreErrors.io();
        }
        this.directory = conversations;
        this.lockChannel = channel;
        this.lock = acquired;
    }

    @Override
    public synchronized ConversationSnapshot loadSnapshot(ConversationKey key) {
        open();
        requireKey(key);
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return new ConversationSnapshot(0L, Collections.emptyList());
        }
        try {
            return codec.decode(Files.readAllBytes(file));
        } catch (IOException e) {
            throw ConversationStoreErrors.io();
        }
    }

    @Override
    public synchronized void append(ConversationKey key, List<IContent> messages, long expectedVersion) {
        open();
        requireKey(key);
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        if (messages.isEmpty()) {
            return;
        }
        ConversationSnapshot current = loadSnapshot(key);
        if (current.version() != expectedVersion) {
            throw ConversationStoreErrors.versionConflict();
        }
        List<IContent> merged = new ArrayList<>(current.messages().size() + messages.size());
        merged.addAll(current.messages());
        for (IContent message : messages) {
            if (message == null) {
                throw new IllegalArgumentException("messages must not contain null");
            }
            merged.add(message);
        }
        ConversationSnapshot next = new ConversationSnapshot(expectedVersion + 1L, merged);
        byte[] bytes = codec.encode(next);
        if (bytes.length > maxFileSizeBytes) {
            throw ConversationStoreErrors.resourceLimit();
        }
        writeAtomic(fileFor(key), bytes);
    }

    @Override
    public synchronized void clear(ConversationKey key) {
        open();
        requireKey(key);
        try {
            Files.deleteIfExists(fileFor(key));
        } catch (IOException e) {
            throw ConversationStoreErrors.io();
        }
    }

    /**
     * 读取目录下全部会话快照：按文件最后修改时间从早到晚排序，损坏的单个文件跳过，不影响其他历史。
     *
     * @return 全部可读快照，非 null
     */
    @Override
    public synchronized List<ConversationSnapshot> loadAllSnapshots() {
        open();
        List<Path> files = historyFiles();
        List<ConversationSnapshot> snapshots = new ArrayList<>(files.size());
        for (Path file : files) {
            try {
                snapshots.add(codec.decode(Files.readAllBytes(file)));
            } catch (IOException | RuntimeException ignored) {
                // 单个文件损坏或正在被替换时跳过，保证历史查看不受影响
            }
        }
        return snapshots;
    }

    /**
     * 清理全部会话中的聊天记录；{@code ALL} 直接删除全部历史文件，按条清理时原子重写剩余内容。
     *
     * @param scope 清理范围，不可为 null
     * @param count 清理条数；{@code ALL} 时忽略，其余范围必须为正
     * @return 实际清理的聊天消息条数
     */
    @Override
    public synchronized int deleteChatMessages(HistoryClearScopeEnum scope, int count) {
        open();
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (scope != HistoryClearScopeEnum.ALL && count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        List<Path> files = historyFiles();
        if (files.isEmpty()) {
            return 0;
        }
        if (scope == HistoryClearScopeEnum.ALL) {
            return deleteAllHistoryFiles(files);
        }
        Map<Path, ConversationSnapshot> snapshots = readableSnapshots(files);
        int total = 0;
        for (ConversationSnapshot snapshot : snapshots.values()) {
            total += chatCount(snapshot.messages());
        }
        if (total == 0) {
            return 0;
        }
        if (count >= total) {
            return deleteAllHistoryFiles(files);
        }
        Map<Path, boolean[]> removals = markChatMessages(snapshots, scope, count);
        return rewriteWithoutMarkedMessages(snapshots, removals);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            lock.release();
        } catch (IOException ignored) {
            // 释放失败不阻断关闭
        }
        closeQuietly(lockChannel);
    }

    private Path fileFor(ConversationKey key) {
        return directory.resolve(sha256(key.value()) + ".bin");
    }

    /**
     * 列出全部历史文件，按最后修改时间从早到晚排序。
     */
    private List<Path> historyFiles() {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .sorted(Comparator.comparingLong(LocalConversationStore::lastModifiedMillis))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw ConversationStoreErrors.io();
        }
    }

    /**
     * 读取可解析的历史快照；损坏文件跳过，与历史查看口径保持一致。
     */
    private Map<Path, ConversationSnapshot> readableSnapshots(List<Path> files) {
        Map<Path, ConversationSnapshot> snapshots = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                snapshots.put(file, codec.decode(Files.readAllBytes(file)));
            } catch (IOException | RuntimeException ignored) {
                // 损坏或正被替换的文件跳过，不阻断其它历史的清理
            }
        }
        return snapshots;
    }

    /**
     * 按范围标记每条消息是否删除。
     */
    private static Map<Path, boolean[]> markChatMessages(Map<Path, ConversationSnapshot> snapshots,
            HistoryClearScopeEnum scope, int count) {
        Map<Path, boolean[]> removals = new LinkedHashMap<>();
        if (scope == HistoryClearScopeEnum.FIRST) {
            markFirst(snapshots, count, removals);
        } else {
            markLast(snapshots, count, removals);
        }
        return removals;
    }

    /**
     * 从最早的历史文件开始标记最早的若干条聊天消息。
     */
    private static void markFirst(Map<Path, ConversationSnapshot> snapshots, int count,
            Map<Path, boolean[]> removals) {
        int left = count;
        for (Map.Entry<Path, ConversationSnapshot> entry : snapshots.entrySet()) {
            List<IContent> messages = entry.getValue().messages();
            boolean[] marks = new boolean[messages.size()];
            for (int index = 0; index < messages.size() && left > 0; index++) {
                if (isChatMessage(messages.get(index))) {
                    marks[index] = true;
                    left--;
                }
            }
            removals.put(entry.getKey(), marks);
        }
    }

    /**
     * 从最新的历史文件开始标记最新的若干条聊天消息。
     */
    private static void markLast(Map<Path, ConversationSnapshot> snapshots, int count,
            Map<Path, boolean[]> removals) {
        List<Map.Entry<Path, ConversationSnapshot>> entries = new ArrayList<>(snapshots.entrySet());
        int left = count;
        for (int fileIndex = entries.size() - 1; fileIndex >= 0; fileIndex--) {
            Map.Entry<Path, ConversationSnapshot> entry = entries.get(fileIndex);
            List<IContent> messages = entry.getValue().messages();
            boolean[] marks = new boolean[messages.size()];
            for (int index = messages.size() - 1; index >= 0 && left > 0; index--) {
                if (isChatMessage(messages.get(index))) {
                    marks[index] = true;
                    left--;
                }
            }
            removals.put(entry.getKey(), marks);
        }
    }

    /**
     * 重写仍有聊天消息的历史文件；聊天消息被清空的历史文件直接删除，避免留下孤立工具消息。
     */
    private int rewriteWithoutMarkedMessages(Map<Path, ConversationSnapshot> snapshots,
            Map<Path, boolean[]> removals) {
        int removed = 0;
        for (Map.Entry<Path, boolean[]> entry : removals.entrySet()) {
            Path file = entry.getKey();
            boolean[] marks = entry.getValue();
            ConversationSnapshot snapshot = snapshots.get(file);
            if (snapshot == null) {
                continue;
            }
            List<IContent> kept = new ArrayList<>(snapshot.messages().size());
            int removedInFile = 0;
            for (int index = 0; index < snapshot.messages().size(); index++) {
                if (marks[index]) {
                    removedInFile++;
                } else {
                    kept.add(snapshot.messages().get(index));
                }
            }
            if (removedInFile == 0) {
                continue;
            }
            removed += removedInFile;
            if (!hasChatMessage(kept)) {
                deleteQuietly(file);
                continue;
            }
            long modified = lastModifiedMillis(file);
            writeAtomic(file, codec.encode(new ConversationSnapshot(snapshot.version() + 1L, kept)));
            restoreLastModified(file, modified);
        }
        return removed;
    }

    /**
     * 删除全部历史文件。
     *
     * @return 删除前统计到的聊天消息条数
     */
    private int deleteAllHistoryFiles(List<Path> files) {
        int removed = 0;
        for (Path file : files) {
            try {
                ConversationSnapshot snapshot = codec.decode(Files.readAllBytes(file));
                removed += chatCount(snapshot.messages());
            } catch (IOException | RuntimeException ignored) {
                // 损坏文件无法统计，直接删除即可
            }
            deleteQuietly(file);
        }
        return removed;
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw ConversationStoreErrors.io();
        }
    }

    /**
     * 恢复历史文件最后修改时间：历史展示按文件时间排序，重写后若保留写入时间会打乱会话先后顺序。
     */
    private void restoreLastModified(Path file, long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(millis));
        } catch (IOException ignored) {
            // 时间恢复失败只影响展示排序，不影响历史内容
        }
    }

    private static int chatCount(List<IContent> messages) {
        int count = 0;
        for (IContent message : messages) {
            if (isChatMessage(message)) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasChatMessage(List<IContent> messages) {
        for (IContent message : messages) {
            if (isChatMessage(message)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 与 {@code /history} 的展示口径一致：仅用户 / 助手且正文非空。
     */
    private static boolean isChatMessage(IContent message) {
        if (message == null) {
            return false;
        }
        RoleEnum role = message.getRoleType();
        if (role != RoleEnum.USER && role != RoleEnum.ASSISTANT) {
            return false;
        }
        Object content = message.toCompletionMessage().get("content");
        if (content instanceof String) {
            return !((String) content).trim().isEmpty();
        }
        if (content instanceof List) {
            for (Object part : (List<?>) content) {
                if (part instanceof Map) {
                    Object text = ((Map<?, ?>) part).get("text");
                    if (text instanceof String && !((String) text).trim().isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void open() {
        if (closed) {
            throw new IllegalStateException("conversation store is closed");
        }
    }

    private void writeAtomic(Path target, byte[] bytes) {
        Path fileName = target.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("target must have a file name");
        }
        Path temp = target.resolveSibling(fileName + ".tmp");
        FileChannel channel = null;
        try {
            channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) {
                    throw new IOException("conversation checkpoint write made no progress");
                }
            }
            channel.force(true);
        } catch (IOException e) {
            throw ConversationStoreErrors.io();
        } finally {
            closeQuietly(channel);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 忽略清理失败
            }
            throw ConversationStoreErrors.io();
        }
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

    /**
     * 读取文件最后修改时间；读取失败时排到最前，避免一个坏文件阻断排序。
     */
    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static void requireKey(ConversationKey key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 忽略关闭失败
            }
        }
    }
}
