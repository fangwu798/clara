package com.clara.core.interaction;

import java.util.Map;

/**
 * 答案 Schema 校验接口：校验用户答案是否符合答案 Schema 约束。
 *
 * <p>core 定义的契约接口，由模块实现；提交反馈时校验答案合法性。
 */
public interface IAnswerSchemaValidator {

    /**
     * 校验答案是否符合 Schema 约束。
     *
     * @param schema 答案 Schema，可为 null（无约束时恒为合法）
     * @param answer 用户答案，不可为 null
     * @return 合法时返回 {@code true}
     */
    boolean isValid(String schema, Map<String, Object> answer);
}
