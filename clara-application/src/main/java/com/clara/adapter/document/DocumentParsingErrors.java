package com.clara.adapter.document;

import com.clara.exception.ClaraException;
import com.clara.exception.ClaraError;
import com.clara.exception.ErrorCategory;

import java.util.Collections;

/**
 * 集中创建文档解析相关的领域错误。
 */
final class DocumentParsingErrors {
    private DocumentParsingErrors() {
    }

    static ClaraException error(String code, ErrorCategory category) {
        return new ClaraException(new ClaraError(code, category, code, false, null, null, null, null, Collections.<String, String>emptyMap()));
    }
}
