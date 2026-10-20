package com.clara.memory.persistence.local;

import com.clara.core.message.IContent;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
