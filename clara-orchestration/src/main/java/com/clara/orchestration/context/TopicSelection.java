package com.clara.orchestration.context;

import com.clara.core.message.IContent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 本轮允许进入上下文的历史消息选择结果。
 *
 * <p>用对象同一性保存消息，而不是用内容相等性：历史里的两条消息即使文本相同，也属于不同轮次，
 * 不能因为内容相同就互相顶替。system 指令不参与话题筛选，由装配器始终保留。
 */
final class TopicSelection {

    private final Set<IContent> included;
    private final TopicRelation relation;
    private final String reason;

    /**
     * 创建话题选择结果。
     *
     * @param included 允许进入上下文的消息，可为 null（视为空）
     * @param relation 当前输入与上一话题的关系，不可为 null
     * @param reason   可读的判定原因，可为 null
     */
    TopicSelection(Set<IContent> included, TopicRelation relation, String reason) {
        Set<IContent> copy = Collections.newSetFromMap(new IdentityHashMap<IContent, Boolean>());
        if (included != null) {
            copy.addAll(included);
        }
        this.included = Collections.unmodifiableSet(copy);
        this.relation = relation == null ? TopicRelation.CONTINUE : relation;
        this.reason = reason;
    }

    /**
     * 判断一条消息是否属于本轮应携带的话题范围。
     *
     * @param message 待判断消息，可为 null
     * @return 属于当前选择范围时返回 true
     */
    boolean includes(IContent message) {
        return message != null && included.contains(message);
    }

    /**
     * 当前输入与上一话题的关系。
     *
     * @return 关系枚举，非 null
     */
    TopicRelation getRelation() {
        return relation;
    }

    /**
     * 判定原因，用于链路追踪与排查。
     *
     * @return 可读原因，可能为 null
     */
    String getReason() {
        return reason;
    }

    /**
     * 是否明确切换到了新话题。
     *
     * @return 明确切换时返回 true
     */
    boolean isShifted() {
        return relation == TopicRelation.SHIFT;
    }

    /**
     * 本轮选择的消息条数。
     *
     * @return 非负条数
     */
    int size() {
        return included.size();
    }
}
