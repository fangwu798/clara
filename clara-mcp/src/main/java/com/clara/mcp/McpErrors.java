package com.clara.mcp;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;

/**
 * 集中创建 clara-mcp 的领域错误。
 *
 * <p>是什么、在哪一层：模块内唯一错误工厂，客户端、注册 / 发现、传输与配置装配共用，保证错误对象的
 * 构造参数一致（code 同时作为 message，明细映射为空）。
 *
 * <p>备注：默认 {@code retryable=false}；只有传输层可判定的瞬时故障才使用带 {@code retryable} 的重载。
 * 错误码来自 {@code com.clara.exception.ErrorCodes}，本类不定义新的错误码。
 */
public final class McpErrors {
    private McpErrors() { }

    /**
     * 创建不可重试的领域错误。
     *
     * @param code     错误码，不可为空白
     * @param category 错误分类，不可为 null
     * @return 领域异常，非 null
     */
    public static ClaraException error(String code, ErrorCategoryEnum category) {
        return error(code, category, false);
    }

    /**
     * 创建领域错误。
     *
     * @param code      错误码，不可为空白
     * @param category  错误分类，不可为 null
     * @param retryable 是否可重试
     * @return 领域异常，非 null
     */
    public static ClaraException error(String code, ErrorCategoryEnum category, boolean retryable) {
        return new ClaraException(ClaraError.builder(code, category)
                .retryable(retryable)
                .build());
    }
}
