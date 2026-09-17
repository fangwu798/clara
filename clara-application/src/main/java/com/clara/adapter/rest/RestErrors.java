package com.clara.adapter.rest;

import com.clara.kernel.*;

/**
 * 集中创建REST相关的领域错误。
 */
final class RestErrors {
    private RestErrors() {
    }

    static int status(ClaraError e) {
        if (e.getCode() != null && e.getCode().endsWith("_NOT_FOUND")) return 404;
        switch (e.getCategory()) {
            case AUTHENTICATION:
                return 401;
            case AUTHORIZATION:
                return 403;
            case CONFLICT:
                return 409;
            case RESOURCE_LIMIT:
                return 429;
            case TIMEOUT:
                return 504;
            case CAPABILITY:
            case EXTERNAL_SERVICE:
                return 503;
            case INTERNAL:
            case UNKNOWN_SIDE_EFFECT:
                return 500;
            default:
                return 400;
        }
    }
}
