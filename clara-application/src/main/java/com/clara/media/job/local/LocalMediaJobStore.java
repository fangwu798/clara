package com.clara.media.job.local;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.media.job.MediaJob;
import com.clara.media.job.MediaJobStatus;
import com.clara.media.job.MediaJobStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 使用本地快照和预写日志持久化媒体任务，并维护到期任务及幂等键索引。
 *
 * <p>常规用法：使用 {@link LocalMediaJobStoreConfig} 指定工作目录，构造后通过 {@code create/get/replace}
 * 配合调度器使用；进程退出前调用 {@link #close()} 刷新快照并释放文件锁。
 *
 * <p>注意：同一存储目录一次只能由一个实例持有，不能作为多进程共享存储。{@code replace} 使用版本号进行
 * 乐观并发控制；不要手工修改快照、WAL 或锁文件，校验失败会按存储损坏处理。
 */
public final class LocalMediaJobStore implements MediaJobStore, Closeable {
    private static final int MAGIC = 0x434D4A53;
    private static final int FORMAT_VERSION = 1;
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_STRING_BYTES = 1024 * 1024;

    private final LocalMediaJobStoreConfig config;
    private final Path directory;
    private final Path snapshotFile;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<String, MediaJob> jobs = new HashMap<String, MediaJob>();
    private final Map<String, String> idempotencyIndex = new HashMap<String, String>();
    private boolean closed;

    public LocalMediaJobStore(LocalMediaJobStoreConfig config) {
        if (config == null) { throw new IllegalArgumentException("config must not be null"); }
        this.config = config;
        this.directory = config.getWorkspaceRoot().resolve(".clara").resolve("state").resolve("media-jobs");
        this.snapshotFile = directory.resolve("jobs.bin");
        FileChannel channel = null;
        FileLock acquired = null;
        try {
            Files.createDirectories(directory);
            channel = FileChannel.open(directory.resolve("store.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            acquired = channel.tryLock();
            if (acquired == null) { throw locked(); }
            loadSnapshot();
            if (!Files.exists(snapshotFile)) {
                persistAndReplace(Collections.<String, MediaJob>emptyMap(),
                        Collections.<String, String>emptyMap());
            }
        } catch (OverlappingFileLockException exception) {
            closeQuietly(channel);
            throw locked();
        } catch (ClaraException exception) {
            releaseQuietly(acquired);
            closeQuietly(channel);
            throw exception;
        } catch (IOException exception) {
            releaseQuietly(acquired);
            closeQuietly(channel);
            throw ioError();
        }
        this.lockChannel = channel;
        this.lock = acquired;
    }

    @Override
    public synchronized void create(MediaJob job) {
        ensureOpen();
        if (job == null) { throw new IllegalArgumentException("job must not be null"); }
        String jobKey = key(job.getTenantId(), job.getJobId());
        if (jobs.containsKey(jobKey)) { throw new IllegalStateException("job already exists"); }
        Map<String, MediaJob> replacement = new HashMap<String, MediaJob>(jobs);
        Map<String, String> replacementIndex = new HashMap<String, String>(idempotencyIndex);
        if (job.getIdempotencyKey() != null) {
            String indexKey = key(job.getTenantId(), job.getIdempotencyKey());
            if (replacementIndex.containsKey(indexKey)) {
                throw new IllegalStateException("idempotency key already exists");
            }
            replacementIndex.put(indexKey, job.getJobId());
        }
        replacement.put(jobKey, job);
        persistAndReplace(replacement, replacementIndex);
    }

    @Override
    public synchronized MediaJob get(String tenantId, String jobId) {
        ensureOpen();
        return jobs.get(key(tenantId, jobId));
    }

    @Override
    public synchronized MediaJob findByIdempotencyKey(String tenantId, String idempotencyKey) {
        ensureOpen();
        String jobId = idempotencyIndex.get(key(tenantId, idempotencyKey));
        return jobId == null ? null : jobs.get(key(tenantId, jobId));
    }

    @Override
    public synchronized List<MediaJob> findDue(long now, int limit) {
        ensureOpen();
        if (limit <= 0) { throw new IllegalArgumentException("limit must be positive"); }
        List<MediaJob> due = new ArrayList<MediaJob>();
        for (MediaJob job : jobs.values()) {
            if (job.getStatus() == MediaJobStatus.RUNNING && job.getNextPollAt() != null
                    && job.getNextPollAt().longValue() <= now) due.add(job);
        }
        Collections.sort(due, DUE_ORDER);
        if (due.size() > limit) due = new ArrayList<MediaJob>(due.subList(0, limit));
        return Collections.unmodifiableList(due);
    }

    @Override
    public synchronized boolean replace(String tenantId, String jobId, long expectedVersion,
            MediaJob replacement) {
        ensureOpen();
        String jobKey = key(tenantId, jobId);
        MediaJob current = jobs.get(jobKey);
        if (current == null || current.getVersion() != expectedVersion) return false;
        if (replacement == null || !tenantId.equals(replacement.getTenantId())
                || !jobId.equals(replacement.getJobId())) {
            throw new IllegalArgumentException("replacement identity differs");
        }
        if (!Objects.equals(current.getIdempotencyKey(), replacement.getIdempotencyKey())
                || !Objects.equals(current.getDeadlineAt(), replacement.getDeadlineAt())) {
            throw new IllegalArgumentException("immutable scheduling identity differs");
        }
        Map<String, MediaJob> updated = new HashMap<String, MediaJob>(jobs);
        updated.put(jobKey, replacement);
        persistAndReplace(updated, new HashMap<String, String>(idempotencyIndex));
        return true;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { lockChannel.close(); }
    }

    private void loadSnapshot() {
        if (!Files.exists(snapshotFile)) return;
        try {
            long size = Files.size(snapshotFile);
            if (size < CHECKSUM_BYTES || size > config.getMaxFileBytes()) { throw corrupted(); }
            byte[] encoded = Files.readAllBytes(snapshotFile);
            int payloadLength = encoded.length - CHECKSUM_BYTES;
            byte[] payload = Arrays.copyOf(encoded, payloadLength);
            byte[] expected = Arrays.copyOfRange(encoded, payloadLength, encoded.length);
            if (!MessageDigest.isEqual(expected, sha256(payload))) { throw corrupted(); }
            decode(payload);
        } catch (ClaraException exception) {
            throw exception;
        } catch (IOException exception) {
            throw corrupted();
        } catch (RuntimeException exception) {
            throw corrupted();
        }
    }

    private void decode(byte[] payload) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) { throw corrupted(); }
        if (!config.getWorkspaceId().equals(readString(input, false))) { throw corrupted(); }
        int count = input.readInt();
        if (count < 0 || count > config.getMaxJobs()) { throw corrupted(); }
        Map<String, MediaJob> loaded = new HashMap<String, MediaJob>();
        Map<String, String> loadedIndex = new HashMap<String, String>();
        for (int i = 0; i < count; i++) {
            MediaJob job = readJob(input);
            String jobKey = key(job.getTenantId(), job.getJobId());
            if (loaded.put(jobKey, job) != null) { throw corrupted(); }
            if (job.getIdempotencyKey() != null) {
                String indexKey = key(job.getTenantId(), job.getIdempotencyKey());
                if (loadedIndex.put(indexKey, job.getJobId()) != null) { throw corrupted(); }
            }
        }
        if (input.read() != -1) { throw corrupted(); }
        jobs.clear();
        jobs.putAll(loaded);
        idempotencyIndex.clear();
        idempotencyIndex.putAll(loadedIndex);
    }

    private void persistAndReplace(Map<String, MediaJob> updated,
            Map<String, String> updatedIndex) {
        if (updated.size() > config.getMaxJobs()) { throw resourceLimit(); }
        byte[] encoded = encode(updated);
        if (encoded.length > config.getMaxFileBytes()) { throw resourceLimit(); }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, "jobs-", ".tmp");
            FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            } finally {
                output.close();
            }
            Files.move(temporary, snapshotFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            jobs.clear();
            jobs.putAll(updated);
            idempotencyIndex.clear();
            idempotencyIndex.putAll(updatedIndex);
        } catch (AtomicMoveNotSupportedException exception) {
            throw ioError();
        } catch (IOException exception) {
            throw ioError();
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private byte[] encode(Map<String, MediaJob> source) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, config.getWorkspaceId(), false);
            List<MediaJob> ordered = new ArrayList<MediaJob>(source.values());
            Collections.sort(ordered, IDENTITY_ORDER);
            output.writeInt(ordered.size());
            for (MediaJob job : ordered) writeJob(output, job);
            output.flush();
            byte[] payload = bytes.toByteArray();
            bytes.write(sha256(payload));
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("in-memory media job encoding failed", exception);
        }
    }

    private static void writeJob(DataOutputStream output, MediaJob job) throws IOException {
        writeString(output, job.getJobId(), false);
        writeString(output, job.getTenantId(), false);
        writeString(output, job.getStatus().name(), false);
        output.writeLong(job.getVersion());
        writeString(output, job.getProviderJobId(), true);
        output.writeInt(job.getProgress());
        writeString(output, job.getErrorCode(), true);
        writeString(output, job.getOutputAssetId(), true);
        output.writeLong(job.getCreatedAt());
        output.writeLong(job.getUpdatedAt());
        writeString(output, job.getIdempotencyKey(), true);
        output.writeBoolean(job.isSubmissionAttempted());
        output.writeInt(job.getPollAttempts());
        writeLong(output, job.getNextPollAt());
        writeLong(output, job.getDeadlineAt());
    }

    private static MediaJob readJob(DataInputStream input) throws IOException {
        String jobId = readString(input, false);
        String tenantId = readString(input, false);
        MediaJobStatus status = MediaJobStatus.valueOf(readString(input, false));
        long version = input.readLong();
        String providerJobId = readString(input, true);
        int progress = input.readInt();
        String errorCode = readString(input, true);
        String outputAssetId = readString(input, true);
        long createdAt = input.readLong();
        long updatedAt = input.readLong();
        String idempotencyKey = readString(input, true);
        boolean submissionAttempted = input.readBoolean();
        int pollAttempts = input.readInt();
        Long nextPollAt = readLong(input);
        Long deadlineAt = readLong(input);
        return new MediaJob(jobId, tenantId, status, version, providerJobId, progress,
                errorCode, outputAssetId, createdAt, updatedAt, idempotencyKey,
                submissionAttempted, pollAttempts, nextPollAt, deadlineAt);
    }

    private static void writeString(DataOutputStream output, String value, boolean nullable)
            throws IOException {
        if (value == null) {
            if (!nullable) { throw new IllegalArgumentException("required value is null"); }
            output.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) { throw resourceLimit(); }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, boolean nullable) throws IOException {
        int length = input.readInt();
        if (length == -1 && nullable) return null;
        if (length < 0 || length > MAX_STRING_BYTES) { throw corrupted(); }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeLong(DataOutputStream output, Long value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) output.writeLong(value.longValue());
    }

    private static Long readLong(DataInputStream input) throws IOException {
        return input.readBoolean() ? Long.valueOf(input.readLong()) : null;
    }

    private static byte[] sha256(byte[] payload) {
        try { return MessageDigest.getInstance("SHA-256").digest(payload); }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String key(String tenantId, String id) {
        if (tenantId == null || id == null) { throw new IllegalArgumentException("identity must not be null"); }
        return tenantId.length() + ":" + tenantId + id;
    }

    private void ensureOpen() {
        if (closed) { throw new IllegalStateException("media job store is closed"); }
    }

    private static ClaraException locked() {
        return error(ErrorCodes.MEDIA_JOB_STORE_LOCKED, ErrorCategory.CONFLICT, true);
    }

    private static ClaraException corrupted() {
        return error(ErrorCodes.MEDIA_JOB_STORE_CORRUPTED, ErrorCategory.INTERNAL, false);
    }

    private static ClaraException ioError() {
        return error(ErrorCodes.MEDIA_JOB_STORE_IO_ERROR, ErrorCategory.INTERNAL, true);
    }

    private static ClaraException resourceLimit() {
        return error(ErrorCodes.MEDIA_JOB_RESOURCE_LIMIT, ErrorCategory.RESOURCE_LIMIT, false);
    }

    private static ClaraException error(String code, ErrorCategory category, boolean retryable) {
        return new ClaraException(new ClaraError(code, category, code, retryable, null,
                null, null, null, Collections.<String, String>emptyMap()));
    }

    private static void releaseQuietly(FileLock value) {
        if (value == null) return;
        try { value.release(); } catch (IOException ignored) { }
    }

    private static void closeQuietly(FileChannel value) {
        if (value == null) return;
        try { value.close(); } catch (IOException ignored) { }
    }

    private static final Comparator<MediaJob> IDENTITY_ORDER = new Comparator<MediaJob>() {
        public int compare(MediaJob left, MediaJob right) {
            int tenant = left.getTenantId().compareTo(right.getTenantId());
            return tenant != 0 ? tenant : left.getJobId().compareTo(right.getJobId());
        }
    };

    private static final Comparator<MediaJob> DUE_ORDER = new Comparator<MediaJob>() {
        public int compare(MediaJob left, MediaJob right) {
            int time = Long.compare(left.getNextPollAt().longValue(), right.getNextPollAt().longValue());
            if (time != 0) return time;
            int tenant = left.getTenantId().compareTo(right.getTenantId());
            return tenant != 0 ? tenant : left.getJobId().compareTo(right.getJobId());
        }
    };
}
