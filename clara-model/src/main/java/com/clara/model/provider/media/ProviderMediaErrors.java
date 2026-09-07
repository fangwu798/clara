package com.clara.model.provider.media;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.model.api.ModelProvider;

import java.util.Collections;

/**
 * 集中创建服务提供商媒体相关的领域错误。
 */
public final class ProviderMediaErrors {
    private ProviderMediaErrors() {
    }

    public static ClaraException credential(ModelProvider provider) {
        return error(ErrorCodes.MODEL_CREDENTIAL_UNAVAILABLE, ErrorCategory.CONFIGURATION,
                false, provider, null);
    }

    public static ClaraException protocol(ModelProvider provider) {
        return error(ErrorCodes.MODEL_PROTOCOL_ERROR, ErrorCategory.EXTERNAL_SERVICE,
                false, provider, null);
    }

    public static ClaraException provider(ModelProvider provider, int status) {
        ErrorCategory category = status == 401 ? ErrorCategory.AUTHENTICATION
                : status == 403 ? ErrorCategory.AUTHORIZATION
                : status == 429 ? ErrorCategory.RATE_LIMIT : ErrorCategory.EXTERNAL_SERVICE;
        return error(ErrorCodes.MODEL_PROVIDER_FAILED, category, status == 429 || status >= 500,
                provider, String.valueOf(status));
    }

    public static ClaraException tooLarge(ModelProvider provider) {
        return error(ErrorCodes.MODEL_REQUEST_TOO_LARGE, ErrorCategory.RESOURCE_LIMIT,
                false, provider, null);
    }

    public static ClaraException transportUnsupported(ModelProvider provider) {
        return error(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, ErrorCategory.CONFIGURATION,
                false, provider, null);
    }

    private static ClaraException error(String code, ErrorCategory category, boolean retryable,
                                        ModelProvider provider, String providerCode) {
        return new ClaraException(new ClaraError(code, category, code, retryable, null,
                provider.name(), providerCode, null, Collections.<String, String>emptyMap()));
    }
}
