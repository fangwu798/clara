package com.clara.planner;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import java.util.Collections;

final class PlannerErrors {
    private PlannerErrors() { }
    static ClaraException error(String code, ErrorCategory category) {
        return new ClaraException(new ClaraError(code, category, code, false,
                null, null, null, null, Collections.<String, String>emptyMap()));
    }
}
