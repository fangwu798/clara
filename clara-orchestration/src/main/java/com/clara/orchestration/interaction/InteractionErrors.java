package com.clara.orchestration.interaction;

import com.clara.kernel.exception.ClaraError;
import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ErrorCategory;
import java.util.Collections;

/**
 * 集中创建交互相关的领域错误。
 */
final class InteractionErrors {
    private InteractionErrors() { }
    static ClaraException error(String code, ErrorCategory category) {
        return new ClaraException(new ClaraError(code, category, code, false,
                null, null, null, null, Collections.<String, String>emptyMap()));
    }
}
