package com.clara.workflow.engine;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;


/**
 * 集中创建工作流领域错误。
 */
final class WorkflowErrors {

    private WorkflowErrors() {
    }

    static ClaraException error(String code, ErrorCategoryEnum category) {
        return new ClaraException(ClaraError.builder(code, category).build());
    }
}
