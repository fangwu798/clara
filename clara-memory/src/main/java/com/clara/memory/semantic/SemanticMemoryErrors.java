package com.clara.memory.semantic;

import com.clara.exception.ClaraError;
import com.clara.exception.ClaraException;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;


/**
 * 集中创建语义记忆领域的错误。
 */
public final class SemanticMemoryErrors {

    private SemanticMemoryErrors() {
    }

    /**
     * 语义记忆被占用：未能取得存储锁。
     */
    public static ClaraException locked() {
        return error(ErrorCodes.SEMANTIC_MEMORY_LOCKED, ErrorCategoryEnum.CONFLICT, true);
    }

    /**
     * 语义记忆损坏：快照或校验信息无法解析。
     */
    public static ClaraException corrupted() {
        return error(ErrorCodes.SEMANTIC_MEMORY_CORRUPTED, ErrorCategoryEnum.INTERNAL, false);
    }

    /**
     * 语义记忆配置不匹配：当前配置与既有存储数据不一致。
     */
    public static ClaraException configMismatch() {
        return error(ErrorCodes.SEMANTIC_MEMORY_CONFIG_MISMATCH, ErrorCategoryEnum.CONFIGURATION, false);
    }

    /**
     * 语义记忆资源超限：记录数、向量规模或查询规模超过配置上限。
     */
    public static ClaraException resourceLimit() {
        return error(ErrorCodes.SEMANTIC_MEMORY_RESOURCE_LIMIT, ErrorCategoryEnum.RESOURCE_LIMIT, false);
    }

    /**
     * 语义记忆后端失败：向量库或持久化后端返回错误。
     */
    public static ClaraException backendFailed(boolean retryable) {
        return error(ErrorCodes.SEMANTIC_MEMORY_BACKEND_FAILED, ErrorCategoryEnum.EXTERNAL_SERVICE, retryable);
    }

    private static ClaraException error(String code, ErrorCategoryEnum category, boolean retryable) {
        return new ClaraException(ClaraError.builder(code, category)
                .retryable(retryable)
                .build());
    }
}
