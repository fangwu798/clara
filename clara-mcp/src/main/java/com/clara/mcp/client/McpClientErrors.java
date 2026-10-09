package com.clara.mcp.client;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCategory;
import java.util.Collections;

/**
 * 集中创建MCP客户端相关的领域错误。
 */
public final class McpClientErrors {
    private McpClientErrors() { }
    public static ClaraException error(String code, ErrorCategory category) {
        return new ClaraException(new ClaraError(code, category, code, false,
                null, null, null, null, Collections.<String, String>emptyMap()));
    }
}
