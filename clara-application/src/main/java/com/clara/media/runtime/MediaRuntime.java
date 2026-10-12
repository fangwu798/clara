package com.clara.media.runtime;

import com.clara.deployment.local.WindowsSingleHostStoragePolicy;
import com.clara.media.MediaStore;
import com.clara.media.filesystem.FileSystemMediaStore;
import com.clara.media.job.DefaultMediaJobScheduler;
import com.clara.media.job.MediaJobScheduler;
import com.clara.media.job.MediaJobSchedulerConfig;
import com.clara.media.job.local.LocalMediaJobStore;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 媒体运行时：按工作区惰性装配媒体存储与异步任务调度，是媒体子系统的唯一装配点。
 *
 * <p>是什么、在哪一层：application 的媒体装配组件（{@code com.clara.media.runtime}），把「文件系统媒体存储 +
 * 本地任务存储 + 任务调度器」按单机存储基线（{@link WindowsSingleHostStoragePolicy}）组装起来，供上层按需获取。
 *
 * <p>为什么惰性：{@link FileSystemMediaStore} 与 {@link LocalMediaJobStore} 在构造期即创建目录并**独占文件锁**，
 * 若在装配期急切构造会给每个进程（含测试）带来副作用与锁竞争。故本类只在首次获取时构造，未使用则零开销。
 *
 * <p>生命周期：本类拥有已构造的媒体存储与任务存储，{@link #close()} 统一释放；线程安全（惰性构造与关闭同步）。
 */
public final class MediaRuntime implements Closeable {

    /**
     * 单机默认租户标识：媒体资产按租户隔离，本模块无租户上下文时统一落到该租户。
     */
    private static final String LOCAL_TENANT = "local";

    /**
     * 任务轮询初始间隔（毫秒）。
     */
    private static final long INITIAL_POLL_DELAY_MS = 1_000L;

    /**
     * 任务轮询最大间隔（毫秒）。
     */
    private static final long MAX_POLL_DELAY_MS = 30_000L;

    /**
     * 任务轮询退避倍数。
     */
    private static final double BACKOFF_MULTIPLIER = 2.0d;

    /**
     * 单任务最大轮询次数。
     */
    private static final int MAX_POLL_ATTEMPTS = 30;

    private final Path workspaceRoot;
    private final String workspaceId;
    private FileSystemMediaStore mediaStore;
    private LocalMediaJobStore mediaJobStore;
    private MediaJobScheduler mediaJobScheduler;

    /**
     * 创建媒体运行时（不产生任何文件或锁，直到首次获取）。
     *
     * @param workspaceRoot 工作区根目录，不可为 null
     * @param workspaceId   工作区标识，不可为空白
     * @throws IllegalArgumentException 参数非法时
     */
    public MediaRuntime(Path workspaceRoot, String workspaceId) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot must not be null");
        }
        if (workspaceId == null || workspaceId.trim().isEmpty()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        this.workspaceRoot = workspaceRoot;
        this.workspaceId = workspaceId;
    }

    /**
     * 媒体资产存储（首次调用时构造并独占资产目录）。
     *
     * @return 媒体存储，非 null
     */
    public synchronized MediaStore mediaStore() {
        if (mediaStore == null) {
            mediaStore = new FileSystemMediaStore(storagePolicy().mediaStoreConfig());
        }
        return mediaStore;
    }

    /**
     * 异步媒体任务调度器（首次调用时构造，并确保媒体存储已就绪）。
     *
     * @return 任务调度器，非 null
     */
    public synchronized MediaJobScheduler mediaJobScheduler() {
        if (mediaJobScheduler == null) {
            mediaJobStore = new LocalMediaJobStore(storagePolicy().mediaJobStoreConfig());
            mediaJobScheduler = new DefaultMediaJobScheduler(mediaJobStore, mediaStore(),
                    new MediaJobSchedulerConfig(INITIAL_POLL_DELAY_MS, MAX_POLL_DELAY_MS,
                            BACKOFF_MULTIPLIER, MAX_POLL_ATTEMPTS));
        }
        return mediaJobScheduler;
    }

    /**
     * 媒体资产所属租户标识。
     *
     * @return 租户标识，非 null
     */
    public String tenantId() {
        return LOCAL_TENANT;
    }

    private WindowsSingleHostStoragePolicy storagePolicy() {
        return new WindowsSingleHostStoragePolicy(workspaceRoot, workspaceId);
    }

    /**
     * 释放已构造的媒体存储与任务存储（未构造时不做任何事）。
     */
    @Override
    public synchronized void close() {
        closeQuietly(mediaJobStore);
        mediaJobStore = null;
        mediaJobScheduler = null;
        closeQuietly(mediaStore);
        mediaStore = null;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 释放失败不阻断其余资源关闭
        }
    }
}
