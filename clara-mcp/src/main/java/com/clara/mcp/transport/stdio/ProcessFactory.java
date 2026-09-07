package com.clara.mcp.transport.stdio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 负责创建进程实例。
 */
public interface ProcessFactory {
    Process start(List<String> command, Path workingDirectory,
            Map<String, String> environment) throws IOException;
}
