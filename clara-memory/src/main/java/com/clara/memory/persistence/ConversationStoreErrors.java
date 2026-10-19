package com.clara.memory.persistence;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;


/**
 * 集中创建会话持久化领域的错误。
 */
public final class ConversationStoreErrors {

    private ConversationStoreErrors() {
    }

    /**
     * 会话版本冲突：基于旧版本写入，期间已被其他请求修改。
     */
    public static ClaraException versionConflict() {
        return error(ErrorCodes.CONVERSATION_VERSION_CONFLICT, ErrorCategoryEnum.CONFLICT, false);
    }

    /**
     * 会话存储被占用：未能取得存储锁。
     */
    public static ClaraException locked() {
        return error(ErrorCodes.CONVERSATION_STORE_LOCKED, ErrorCategoryEnum.CONFLICT, true);
    }

    /**
     * 会话存储损坏：文件校验失败或内容无法解析。
     */
    public static ClaraException corrupted() {
        return error(ErrorCodes.CONVERSATION_STORE_CORRUPTED, ErrorCategoryEnum.INTERNAL, false);
    }

    /**
     * 会话存储读写失败：磁盘 IO 异常。
     */
    public static ClaraException io() {
        return error(ErrorCodes.CONVERSATION_STORE_IO_ERROR, ErrorCategoryEnum.INTERNAL, true);
    }

    /**
     * 会话存储资源超限：单会话文件大小超过配置上限。
     */
    public static ClaraException resourceLimit() {
        return new ClaraException(ClaraError.builder("CONVERSATION_STORE_RESOURCE_LIMIT", ErrorCategoryEnum.RESOURCE_LIMIT)
                .safeMessage("会话存储资源超限：单会话文件大小超过配置上限")
                .build());
    }

    private static ClaraException error(String code, ErrorCategoryEnum category, boolean retryable) {
        return new ClaraException(ClaraError.builder(code, category)
                .retryable(retryable)
                .build());
    }
}
