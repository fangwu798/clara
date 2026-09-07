package com.clara.mcp.transport;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import java.util.Collections;

/**
 * 集中创建MCP传输器相关的领域错误。
 */
public final class McpTransportErrors {
    private McpTransportErrors() { }
    public static ClaraException error(String code, ErrorCategory category, boolean retryable) {
        return new ClaraException(new ClaraError(code, category, code, retryable,
                null, null, null, null, Collections.<String, String>emptyMap()));
    }
}
