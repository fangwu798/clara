package com.clara.core.memory;

import com.clara.core.message.IContent;

import java.util.List;

/**
 * Token 估算器：估算一批历史消息的 token 数，作为百分比维度的计算依据。
 */
public interface ITokenCountEstimator {

    /**
     * 估算一批消息的 token 数。
     *
     * @param messages 历史消息，不可为 null
     * @return 非负的 token 估算数
     */
    int estimate(List<IContent> messages);
}
