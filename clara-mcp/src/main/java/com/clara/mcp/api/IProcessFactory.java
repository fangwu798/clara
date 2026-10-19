package com.clara.mcp.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * stdio 传输的进程创建 SPI：为 MCP 服务端启动本地子进程。
 *
 * <p>使用方：{@code StdioMcpTransport} 在 {@code start} 时调用；默认实现继承父进程环境变量，
 * 再叠加配置中的 {@code env}。测试可替换为假实现，避免启动真实进程。
 *
 * <p>实现约定：返回的进程必须可读写标准输入输出；{@code command} 首项应为已校验的绝对路径，
 * 允许清单校验由传输层完成。创建失败抛 {@link IOException}，传输层会映射为
 * {@code MCP_TRANSPORT_IO_ERROR}。
 *
 * <p>备注：本接口不负责销毁进程；生命周期由 {@code StdioMcpTransport#close()} 统一管理。
 */
public interface IProcessFactory {
    /**
     * 启动一个子进程。
     *
     * @param command          完整命令行，首项为可执行文件路径，不可为 null 或空
     * @param workingDirectory 子进程工作目录，不可为 null
     * @param environment      最终环境变量（已包含继承值），不可为 null
     * @return 已启动的进程，非 null
     * @throws IOException 进程创建失败
     */
    Process start(List<String> command, Path workingDirectory, Map<String, String> environment) throws IOException;
}
