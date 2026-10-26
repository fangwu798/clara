package com.clara.orchestration.context;

import com.clara.core.message.IContent;
import com.clara.dto.memory.TopicIsolationConfig;
import com.clara.kernel.message.delta.AssistantContent;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link TopicSegmentPlanner} 单元测试：话题段选择范围与显式引用旧话题。
 */
public class TopicSegmentPlannerTest {

    private static IContent user(String text) {
        return new UserContent(text);
    }

    private static IContent assistant(String text) {
        return new AssistantContent(text);
    }

    private static TopicSegmentPlanner planner() {
        return new TopicSegmentPlanner(TopicIsolationConfig.defaults());
    }

    @Test
    public void shiftDropsPreviousTopicMessages() {
        IContent a1 = user("订单服务异常怎么排查");
        IContent a2 = assistant("先检查订单服务日志");
        IContent b = user("今天天气怎么样");
        List<IContent> history = Arrays.asList(a1, a2, b);

        TopicSelection selection = planner().plan(history, "今天天气怎么样");

        assertEquals(TopicRelation.SHIFT, selection.getRelation());
        assertFalse(selection.includes(a1));
        assertFalse(selection.includes(a2));
        assertTrue(selection.includes(b));
    }

    @Test
    public void continueKeepsWholeCurrentSegment() {
        IContent a1 = user("订单服务异常怎么排查");
        IContent a2 = assistant("先检查订单服务日志");
        IContent a3 = user("订单服务异常的调用方有哪些");
        List<IContent> history = Arrays.asList(a1, a2, a3);

        TopicSelection selection = planner().plan(history, "订单服务异常的调用方有哪些");

        assertEquals(TopicRelation.CONTINUE, selection.getRelation());
        assertTrue(selection.includes(a1));
        assertTrue(selection.includes(a2));
        assertTrue(selection.includes(a3));
    }

    @Test
    public void explicitReferenceBringsBackReferencedTopicOnly() {
        IContent a1 = user("订单服务异常怎么排查");
        IContent a2 = assistant("先检查订单服务日志");
        IContent b1 = user("今天天气怎么样");
        IContent b2 = assistant("今天晴");
        IContent c = user("继续刚才订单服务的问题");
        List<IContent> history = Arrays.asList(a1, a2, b1, b2, c);

        TopicSelection selection = planner().plan(history, "继续刚才订单服务的问题");

        assertEquals(TopicRelation.EXPLICIT_REFERENCE, selection.getRelation());
        assertTrue(selection.includes(a1));
        assertTrue(selection.includes(a2));
        assertFalse(selection.includes(b1));
        assertFalse(selection.includes(b2));
        assertTrue(selection.includes(c));
    }

    @Test
    public void ambiguousKeepsConfiguredPreviousTurns() {
        IContent a1 = user("订单服务异常怎么排查");
        IContent a2 = assistant("先检查订单服务日志");
        IContent b = user("那这个呢");
        List<IContent> history = Arrays.asList(a1, a2, b);

        TopicSelection selection = planner().plan(history, "那这个呢");

        assertEquals(TopicRelation.AMBIGUOUS, selection.getRelation());
        assertTrue(selection.includes(a1));
        assertTrue(selection.includes(a2));
        assertTrue(selection.includes(b));
    }

    @Test
    public void disabledTopicIsolationKeepsAllHistory() {
        IContent a1 = user("订单服务异常怎么排查");
        IContent a2 = assistant("先检查订单服务日志");
        IContent b = user("今天天气怎么样");
        List<IContent> history = Arrays.asList(a1, a2, b);

        TopicSelection selection = new TopicSegmentPlanner(TopicIsolationConfig.disabled())
                .plan(history, "今天天气怎么样");

        assertTrue(selection.includes(a1));
        assertTrue(selection.includes(a2));
        assertTrue(selection.includes(b));
    }

    @Test
    public void interventionStaysInCurrentSegment() {
        IContent a1 = user("分析订单服务");
        IContent a2 = assistant("正在分析");
        IContent intervention = user("[用户实时补充] 只看 src 目录");
        List<IContent> history = Arrays.asList(a1, a2, intervention);

        TopicSelection selection = planner().plan(history, "[用户实时补充] 只看 src 目录");

        assertEquals(TopicRelation.CONTINUE, selection.getRelation());
        assertTrue(selection.includes(a1));
        assertTrue(selection.includes(a2));
        assertTrue(selection.includes(intervention));
    }
}
