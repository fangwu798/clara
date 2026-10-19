package com.clara.workflow.planner;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;


/**
 * 集中创建规划领域错误。
 */
final class PlannerErrors {

    private PlannerErrors() {
    }

    static ClaraException error(String code, ErrorCategoryEnum category) {
        return new ClaraException(ClaraError.builder(code, category).build());
    }
}
