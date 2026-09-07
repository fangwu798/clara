package com.clara.mcp.registry;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import java.util.Collections;

/**
 * 集中创建MCP相关的领域错误。
 */
final class McpErrors {
    private McpErrors() { }
    static ClaraException error(String code, ErrorCategory category) {
        return new ClaraException(new ClaraError(code, category, code, false,
                null, null, null, null, Collections.<String, String>emptyMap()));
    }
}
