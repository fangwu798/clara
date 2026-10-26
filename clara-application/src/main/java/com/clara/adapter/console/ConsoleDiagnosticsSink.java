package com.clara.adapter.console;

import com.clara.core.diagnostics.IDiagnosticsSink;

/**
 * 控制台诊断输出：CLI 启动时安装，把库内的进度信息与告警还原成终端输出。
 *
 * <p>库模块默认不写标准输出，只有安装了本端口才会展示进度行；
 * 嵌入宿主可以安装自己的实现，或保持默认（进度静默、告警写标准错误）。
 */
public final class ConsoleDiagnosticsSink implements IDiagnosticsSink {

    /**
     * 进度信息写标准输出。
     *
     * @param message 诊断文本
     */
    @Override
    public void info(String message) {
        System.out.println(message);
    }

    /**
     * 告警写标准错误。
     *
     * @param message 告警文本
     */
    @Override
    public void warn(String message) {
        System.err.println(message);
    }
}