package com.clara.orchestration;

import com.clara.enums.CapabilityKindEnum;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;

/**
 * 编排领域错误工厂：集中创建编排层错误，统一错误码与分类。
 */
public final class OrchestrationErrors {

    private OrchestrationErrors() {
    }

    /**
     * 创建入参非法错误。
     *
     * @param message 安全文案，不可为 null
     * @return 编排错误，非 null
     */
    public static ClaraException invalidArgument(String message) {
        return new ClaraException(ClaraError.builder(ErrorCodes.INVALID_ARGUMENT, ErrorCategoryEnum.VALIDATION)
                .safeMessage(message)
                .build());
    }

    /**
     * 创建能力不支持错误。
     *
     * @param kind 不受支持的能力种类
     * @return 编排错误，非 null
     */
    public static ClaraException capabilityNotSupported(CapabilityKindEnum kind) {
        return new ClaraException(ClaraError.builder(ErrorCodes.CAPABILITY_NOT_SUPPORTED, ErrorCategoryEnum.CAPABILITY)
                .safeMessage("capability is not supported by orchestration: " + kind)
                .build());
    }

    /**
     * 创建工具往返次数超限错误。
     *
     * @param limit 允许的最大往返次数
     * @return 编排错误，非 null
     */
    public static ClaraException toolRoundTripLimitExceeded(int limit) {
        return new ClaraException(ClaraError.builder(ErrorCodes.INVALID_ARGUMENT, ErrorCategoryEnum.RESOURCE_LIMIT)
                .safeMessage("tool round trip limit exceeded: " + limit)
                .build());
    }
}
