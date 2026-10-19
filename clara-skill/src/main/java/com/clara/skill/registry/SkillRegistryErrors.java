package com.clara.skill.registry;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;


/**
 * 集中创建技能注册与加载领域的错误。
 *
 * <p>定位：扫描目录、解析文件、去重校验过程中的失败（文件非法 / 运行类型不受支持 / 标识重复 /
 * 文件读取失败）统一在此构造，供 {@link SkillLoader} 与 {@link SkillRegistry} 使用。
 */
public final class SkillRegistryErrors {

    private SkillRegistryErrors() {
    }

    /**
     * 构造「技能文件非法」的异常。
     *
     * @param path   文件路径
     * @param reason 失败原因，可为 null
     * @return 入参校验类、不可重试的异常
     */
    public static ClaraException invalidFile(String path, String reason) {
        return error(ErrorCodes.SKILL_SCHEMA_INVALID, ErrorCategoryEnum.VALIDATION, false,
                "技能文件非法：" + path + (reason == null || reason.trim().isEmpty() ? "" : "，" + reason));
    }

    /**
     * 构造「脚本运行类型不受支持」的异常。
     *
     * @param type 文件内声明的运行类型，可为 null
     * @return 入参校验类、不可重试的异常
     */
    public static ClaraException unsupportedType(String type) {
        return error(ErrorCodes.SKILL_SCHEMA_INVALID, ErrorCategoryEnum.VALIDATION, false,
                "脚本运行类型不受支持：" + type);
    }

    /**
     * 构造「技能标识重复」的异常。
     *
     * @param id 重复的标识
     * @return 冲突类、不可重试的异常
     */
    public static ClaraException duplicateId(String id) {
        return error(ErrorCodes.SKILL_VERSION_EXISTS, ErrorCategoryEnum.CONFLICT, false, "技能标识重复：" + id);
    }

    /**
     * 构造「技能文件读取失败」的异常。
     *
     * @param path 文件路径
     * @return 外部服务类、不可重试的异常
     */
    public static ClaraException ioError(String path) {
        return error(ErrorCodes.SKILL_UNAVAILABLE, ErrorCategoryEnum.EXTERNAL_SERVICE, false, "技能文件读取失败：" + path);
    }

    /**
     * 按错误码、分类与可重试标记构造注册异常。
     */
    private static ClaraException error(String code, ErrorCategoryEnum category, boolean retryable, String message) {
        return new ClaraException(ClaraError.builder(code, category)
                .safeMessage(message)
                .retryable(retryable)
                .build());
    }
}
