//package com.clara.memory.persistence.local;
//
//import com.clara.kernel.exception.ClaraException;
//import com.clara.kernel.exception.ErrorCategory;
//import com.clara.kernel.exception.ErrorCodes;
//import com.clara.memory.api.ConversationSnapshot;
//import com.clara.memory.api.ConversationStore;
//import com.clara.memory.persistence.ConversationCodec;
//import com.clara.memory.persistence.ConversationStoreErrors;
//import com.clara.kernel.ChatMessage;
//import java.io.Closeable;
//import java.io.IOException;
//import java.nio.ByteBuffer;
//import java.nio.channels.FileChannel;
//import java.nio.channels.FileLock;
//import java.nio.channels.OverlappingFileLockException;
//import java.nio.file.AtomicMoveNotSupportedException;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.StandardCopyOption;
//import java.nio.file.StandardOpenOption;
//import java.security.MessageDigest;
//import java.security.NoSuchAlgorithmException;
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.List;
//
///**
// * 使用工作区内的本地文件持久化对话消息，并通过版本号提供乐观并发控制。
// *
// * <p>常规用法：以固定工作区构造实例，使用 {@link #loadSnapshot} 取得消息及版本，再将该版本传给
// * {@link #append}；清空会话使用 {@link #clear}，进程退出前调用 {@link #close()} 释放目录锁。构造时只
// * 创建目录并取得锁，不会预加载历史会话；程序重启后按会话 ID 读取对应文件即可恢复历史。
// *
// * <p>注意：同一目录只允许一个存储实例持有，不支持多进程共享。会话 ID 会被严格校验，文件大小受构造参数
// * 限制；版本冲突时应重新加载快照，不应覆盖写入或直接编辑持久化文件。每次读取仍会完整反序列化指定
// * 会话，超长会话应配合窗口裁剪、归档策略或分段存储，不能仅依赖文件大小上限控制堆内存峰值。
// */
//public final class LocalConversationStore implements ConversationStore, Closeable {
//    private static final long DEFAULT_MAX_FILE_BYTES = 128L * 1024L * 1024L;
//    private final Path directory;
//    private final long maxFileBytes;
//    private final ConversationCodec codec = new ConversationCodec();
//    private final FileChannel lockChannel;
//    private final FileLock lock;
//    private boolean closed;
//
//    public LocalConversationStore(Path workspace) {
//        this(workspace, DEFAULT_MAX_FILE_BYTES);
//    }
//
//    public LocalConversationStore(Path workspace, long maxFileBytes) {
//        if (workspace == null) { throw new IllegalArgumentException("workspace must not be null"); }
//        if (maxFileBytes <= 0L) { throw new IllegalArgumentException("maxFileBytes must be positive"); }
//        this.directory = workspace.toAbsolutePath().normalize()
//                .resolve(".clara").resolve("state").resolve("conversations");
//        this.maxFileBytes = maxFileBytes;
//        FileChannel channel = null;
//        FileLock acquiredLock = null;
//        try {
//            Files.createDirectories(directory);
//            channel = FileChannel.open(directory.resolve("store.lock"),
//                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
//            acquiredLock = channel.tryLock();
//            if (acquiredLock == null) { throw locked(); }
//        } catch (OverlappingFileLockException exception) {
//            closeQuietly(channel);
//            throw locked();
//        } catch (ClaraException exception) {
//            closeQuietly(channel);
//            throw exception;
//        } catch (IOException exception) {
//            closeQuietly(channel);
//            throw ioError();
//        }
//        this.lockChannel = channel;
//        this.lock = acquiredLock;
//    }
//
//    @Override
//    public synchronized List<ChatMessage> load(String conversationId) {
//        return loadSnapshot(conversationId).getMessages();
//    }
//
//    @Override
//    public synchronized ConversationSnapshot loadSnapshot(String conversationId) {
//        ensureOpen();
//        Path file = conversationFile(conversationId);
//        if (!Files.exists(file)) {
//            return new ConversationSnapshot(0L, Collections.<ChatMessage>emptyList());
//        }
//        try {
//            long size = Files.size(file);
//            if (size > maxFileBytes) { throw corrupted(); }
//            return codec.decode(conversationId, Files.readAllBytes(file));
//        } catch (ClaraException exception) {
//            throw exception;
//        } catch (IOException exception) {
//            throw corrupted();
//        }
//    }
//
//    @Override
//    public synchronized void append(String conversationId, List<ChatMessage> messages,
//            long expectedVersion) {
//        ensureOpen();
//        requireMessages(messages);
//        ConversationSnapshot current = loadSnapshot(conversationId);
//        if (current.getVersion() != expectedVersion) { throw versionConflict(); }
//        List<ChatMessage> combined = new ArrayList<ChatMessage>(current.getMessages());
//        combined.addAll(messages);
//        write(conversationId, new ConversationSnapshot(expectedVersion + 1L, combined));
//    }
//
//    @Override
//    public synchronized void clear(String conversationId) {
//        ensureOpen();
//        try {
//            Files.deleteIfExists(conversationFile(conversationId));
//        } catch (IOException exception) {
//            throw ioError();
//        }
//    }
//
//    @Override
//    public synchronized void close() throws IOException {
//        if (closed) return;
//        closed = true;
//        try {
//            lock.release();
//        } finally {
//            lockChannel.close();
//        }
//    }
//
//    private void write(String conversationId, ConversationSnapshot snapshot) {
//        Path temporary = null;
//        try {
//            byte[] encoded = codec.encode(conversationId, snapshot);
//            if (encoded.length > maxFileBytes) { throw ioError(); }
//            temporary = Files.createTempFile(directory, "conversation-", ".tmp");
//            FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
//                    StandardOpenOption.TRUNCATE_EXISTING);
//            try {
//                ByteBuffer buffer = ByteBuffer.wrap(encoded);
//                while (buffer.hasRemaining()) output.write(buffer);
//                output.force(true);
//            } finally {
//                output.close();
//            }
//            Files.move(temporary, conversationFile(conversationId),
//                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
//            temporary = null;
//        } catch (AtomicMoveNotSupportedException exception) {
//            throw ioError();
//        } catch (ClaraException exception) {
//            throw exception;
//        } catch (IOException exception) {
//            throw ioError();
//        } finally {
//            if (temporary != null) {
//                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
//            }
//        }
//    }
//
//    private Path conversationFile(String conversationId) {
//        if (conversationId == null || conversationId.trim().isEmpty()) {
//            throw new IllegalArgumentException("conversationId must not be blank");
//        }
//        return directory.resolve(sha256(conversationId) + ".bin");
//    }
//
//    private static String sha256(String value) {
//        try {
//            byte[] digest = MessageDigest.getInstance("SHA-256")
//                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
//            StringBuilder result = new StringBuilder(digest.length * 2);
//            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
//            return result.toString();
//        } catch (NoSuchAlgorithmException exception) {
//            throw new IllegalStateException("SHA-256 is unavailable", exception);
//        }
//    }
//
//    private static void requireMessages(List<ChatMessage> messages) {
//        if (messages == null || messages.isEmpty()) {
//            throw new IllegalArgumentException("messages must not be empty");
//        }
//        if (messages.contains(null)) { throw new IllegalArgumentException("messages must not contain null"); }
//    }
//
//    private void ensureOpen() {
//        if (closed) { throw new IllegalStateException("conversation store is closed"); }
//    }
//
//    private static ClaraException locked() {
//        return ConversationStoreErrors.error(ErrorCodes.CONVERSATION_STORE_LOCKED,
//                ErrorCategory.CONFLICT, true);
//    }
//    private static ClaraException corrupted() {
//        return ConversationStoreErrors.error(ErrorCodes.CONVERSATION_STORE_CORRUPTED,
//                ErrorCategory.INTERNAL, false);
//    }
//    private static ClaraException ioError() {
//        return ConversationStoreErrors.error(ErrorCodes.CONVERSATION_STORE_IO_ERROR,
//                ErrorCategory.INTERNAL, true);
//    }
//    private static ClaraException versionConflict() {
//        return ConversationStoreErrors.error(ErrorCodes.CONVERSATION_VERSION_CONFLICT,
//                ErrorCategory.CONFLICT, false);
//    }
//    private static void closeQuietly(FileChannel channel) {
//        if (channel == null) return;
//        try { channel.close(); } catch (IOException ignored) { }
//    }
//}
