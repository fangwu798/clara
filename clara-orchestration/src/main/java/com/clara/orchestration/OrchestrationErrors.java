package com.clara.orchestration;

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

    /**
     * 创建「模型输出达到长度上限且工具调用参数被截断」错误。
     *
     * <p>为什么不继续执行：截断的 JSON 可能仍能解析，但写文件、改代码等带副作用的参数已经不完整，
     * 直接执行会留下半截结果。这里明确失败，让调用方拆小任务或减少单次输出后重试。
     *
     * @return 资源限制类、不可重试的编排错误
     */
    public static ClaraException outputTruncated() {
        return new ClaraException(ClaraError.builder(ErrorCodes.MODEL_OUTPUT_TRUNCATED,
                ErrorCategoryEnum.RESOURCE_LIMIT)
                .safeMessage("模型输出达到长度上限，工具调用参数被截断")
                .build());
    }
}
