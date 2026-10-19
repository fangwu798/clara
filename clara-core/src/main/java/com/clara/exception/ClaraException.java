package com.clara.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Clara 领域错误的运行时载体：把 {@link ClaraError} 抛出给上层。
 *
 * <p>是什么、在哪一层：内核异常包（{@code com.clara.kernel.exception}）中的运行时异常，由各模块的
 * 错误工厂（如 {@code ExceptionUtils}）构造并抛出；上层在 catch 块里通过 {@link #getError()} 取回
 * 结构化错误，据此读取错误码与可重试标记。
 *
 * <p>携带内容：异常消息取自 {@link ClaraError#getSafeMessage()}（非 null）；本类不额外保存 cause，
 * 是否可重试由 {@link ClaraError#isRetryable()} 表达。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ClaraException extends RuntimeException {

    /**
     * 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 本次失败的结构化错误，非 null。
     */
    private final ClaraError error;

    /**
     * 创建异常，异常消息取自错误的 {@code safeMessage}。
     *
     * @param error 结构化错误，不可为 null
     * @throws IllegalArgumentException error 为 null 时
     */
    public ClaraException(ClaraError error) {
        super(requireError(error).getSafeMessage());
        this.error = error;
    }


    /**
     * 校验结构化错误非 null。
     *
     * @param error 结构化错误，可为 null
     * @return 原样返回的 error，非 null
     * @throws IllegalArgumentException error 为 null 时
     */
    private static ClaraError requireError(ClaraError error) {
        if (error == null) {
            throw new IllegalArgumentException("error must not be null");
        }
        return error;
    }

}
