package com.clara.workspace.runtime;

import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ClaraError;
import com.clara.kernel.exception.ErrorCategory;

import java.util.Collections;

/**
 * 集中创建工作区相关的领域错误。
 */
final class WorkspaceErrors {
    private WorkspaceErrors() {
    }

    static ClaraException error(String code, ErrorCategory category) {
        return new ClaraException(new ClaraError(code, category, code, false, null, null, null, null, Collections.<String, String>emptyMap()));
    }
}
