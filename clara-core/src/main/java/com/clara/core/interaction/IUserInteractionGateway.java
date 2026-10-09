package com.clara.core.interaction;

import com.clara.dto.interaction.InteractionContext;
import com.clara.dto.interaction.InteractionRequest;
import com.clara.dto.interaction.Question;

/**
 * 向用户发起交互请求的统一入口。
 *
 * <p>core 定义的契约接口，由宿主或模块实现；把「发起交互」与「如何呈现给用户」解耦。
 */
public interface IUserInteractionGateway {

    /**
     * 发起一次交互请求。
     *
     * @param context  交互上下文，不可为 null
     * @param question 需要用户回答的问题，不可为 null
     * @return 交互请求，非 null
     */
    InteractionRequest request(InteractionContext context, Question question);
}
