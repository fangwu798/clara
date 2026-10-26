package com.clara.core.diagnostics;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 诊断输出的全局入口：进程内共享一个输出端口。
 *
 * <p>未安装端口时 {@link #warn(String)} 写标准错误、{@link #info(String)} 静默；
 * CLI 启动时通过 {@link #install(IDiagnosticsSink)} 注入控制台输出，
 * 嵌入宿主可以注入自己的日志实现或调用 {@link #reset()} 恢复默认行为。
 */
public final class Diagnostics {

    /**
     * 当前输出端口；null 表示未安装。
     */
    private static final AtomicReference<IDiagnosticsSink> SINK = new AtomicReference<>();

    private Diagnostics() {
    }

    /**
     * 安装诊断输出端口。
     *
     * @param sink 输出端口，传 null 等同 {@link #reset()}
     */
    public static void install(IDiagnosticsSink sink) {
        SINK.set(sink);
    }

    /**
     * 恢复默认行为：进度信息静默，告警写标准错误。
     */
    public static void reset() {
        SINK.set(null);
    }

    /**
     * 输出进度信息；未安装端口时静默。
     *
     * @param message 诊断文本，可为 null（忽略）
     */
    public static void info(String message) {
        IDiagnosticsSink sink = SINK.get();
        if (sink != null && message != null) {
            sink.info(message);
        }
    }

    /**
     * 输出告警；未安装端口时写标准错误，保证降级不被静默吞掉。
     *
     * @param message 告警文本，可为 null（忽略）
     */
    public static void warn(String message) {
        if (message == null) {
            return;
        }
        IDiagnosticsSink sink = SINK.get();
        if (sink != null) {
            sink.warn(message);
            return;
        }
        System.err.println(message); // NOSONAR 未安装端口时的兜底告警输出
    }
}