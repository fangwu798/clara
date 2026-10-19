package com.clara.skill;

import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;


/**
 * 集中创建技能领域的错误。
 *
 * <p>定位：技能「不存在 / 权限被拒 / Schema 非法 / 超时 / 执行失败 / 运行类型未注册」等失败的
 * 错误码与分类映射集中在此，供执行、注册与工具调用链路共用，避免各实现类各写一份私有错误工厂。
 *
 * <p>使用方法：本类只构造、不抛出，调用方需显式 {@code throw SkillErrors.skillNotFound(id);}，
 * 让错误码与可重试语义在调用点可见。
 */
public final class SkillErrors {

    private SkillErrors() {
    }

    /**
     * 构造「技能不存在」的异常。
     *
     * @param id 技能 / 工具标识
     * @return 入参校验类、不可重试的异常
     */
    public static ClaraException skillNotFound(String id) {
        return error(ErrorCodes.SKILL_NOT_FOUND, ErrorCategoryEnum.VALIDATION, false, "技能不存在：" + id);
    }

    /**
     * 构造「技能权限被拒」的异常。
     *
     * @param id 技能 / 工具标识
     * @return 授权类、不可重试的异常
     */
    public static ClaraException permissionDenied(String id) {
        return error(ErrorCodes.SKILL_PERMISSION_DENIED, ErrorCategoryEnum.AUTHORIZATION, false, "技能权限被拒：" + id);
    }

    /**
     * 构造「技能 Schema 非法」的异常。
     *
     * @param detail 失败细节
     * @return 入参校验类、不可重试的异常
     */
    public static ClaraException schemaInvalid(String detail) {
        return error(ErrorCodes.SKILL_SCHEMA_INVALID, ErrorCategoryEnum.VALIDATION, false, detail);
    }

    /**
     * 构造「技能执行超时」的异常。
     *
     * @param id 技能 / 工具标识
     * @return 超时类、可重试的异常
     */
    public static ClaraException timeout(String id) {
        return error(ErrorCodes.SKILL_TIMEOUT, ErrorCategoryEnum.TIMEOUT, true, "技能执行超时：" + id);
    }

    /**
     * 构造「运行类型对应的执行器未注册」的异常。
     *
     * @param type 脚本运行类型，可为 null
     * @return 能力类、不可重试的异常
     */
    public static ClaraException handlerNotFound(ScriptTypeEnum type) {
        return error(ErrorCodes.SKILL_HANDLER_NOT_FOUND, ErrorCategoryEnum.CAPABILITY, false,
                "技能处理入口不存在：" + (type == null ? "null" : type.name()));
    }

    /**
     * 构造「脚本执行失败」的异常（非零退出码、进程启动 / 读写失败等）。
     *
     * @param detail 失败细节
     * @return 外部服务类、不可重试的异常
     */
    public static ClaraException executionFailed(String detail) {
        return error(ErrorCodes.SKILL_UNAVAILABLE, ErrorCategoryEnum.EXTERNAL_SERVICE, false, detail);
    }

    /**
     * 构造「入参非法」的异常。
     *
     * @param detail 失败细节
     * @return 入参校验类、不可重试的异常
     */
    public static ClaraException invalidArgument(String detail) {
        return error(ErrorCodes.INVALID_ARGUMENT, ErrorCategoryEnum.VALIDATION, false, detail);
    }

    /**
     * 构造「执行已被取消」的异常。
     *
     * @return 取消类、不可重试的异常
     */
    public static ClaraException cancelled() {
        return new ClaraException(ClaraError.cancelled(null));
    }

    /**
     * 按错误码、分类与可重试标记构造技能异常。
     *
     * @param code      错误码
     * @param category  错误分类
     * @param retryable 是否可重试
     * @param message   面向使用者的安全文案
     * @return 技能异常，非 null
     */
    private static ClaraException error(String code, ErrorCategoryEnum category, boolean retryable, String message) {
        return new ClaraException(ClaraError.builder(code, category)
                .safeMessage(message)
                .retryable(retryable)
                .build());
    }
}
