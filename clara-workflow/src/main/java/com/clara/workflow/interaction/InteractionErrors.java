package com.clara.workflow.interaction;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;


/**
 * 集中创建交互领域错误。
 */
final class InteractionErrors {

    private InteractionErrors() {
    }

    static ClaraException error(String code, ErrorCategoryEnum category) {
        return new ClaraException(ClaraError.builder(code, category).build());
    }
}
