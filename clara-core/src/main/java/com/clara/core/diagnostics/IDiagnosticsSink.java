package com.clara.core.diagnostics;

/**
 * 诊断输出端口：库内部的进度信息与降级告警通过它输出，由宿主决定是否展示。
 *
 * <p>默认实现（未安装时）只把 {@link #warn} 写到标准错误，{@link #info} 静默，
 * 避免嵌入宿主后被 CLI 的进度输出污染标准输出。
 */
public interface IDiagnosticsSink {

    /**
     * 输出进度类信息。
     *
     * @param message 诊断文本，非 null
     */
    void info(String message);

    /**
     * 输出降级或异常告警。
     *
     * @param message 告警文本，非 null
     */
    void warn(String message);
}