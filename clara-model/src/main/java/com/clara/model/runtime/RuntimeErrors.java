package com.clara.model.runtime;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import java.util.Collections;

/**
 * 集中创建运行时相关的领域错误。
 */
final class RuntimeErrors {
    private RuntimeErrors() {}
    static ClaraException configuration(String code) {
        return error(code, ErrorCategory.CONFIGURATION);
    }
    static ClaraException capability(String code) {
        return error(code, ErrorCategory.CAPABILITY);
    }
    private static ClaraException error(String code, ErrorCategory category) {
        ClaraError error = new ClaraError(code, category, code, false,
                null, null, null, null, Collections.emptyMap());
        return new ClaraException(error);
    }
}
