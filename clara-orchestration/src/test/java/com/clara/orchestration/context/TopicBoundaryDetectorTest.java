package com.clara.orchestration.context;

import com.clara.dto.memory.TopicIsolationConfig;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * {@link TopicBoundaryDetector} 单元测试：相似追问、无关新话题、显式引用与短追问。
 */
public class TopicBoundaryDetectorTest {

    private final TopicBoundaryDetector detector =
            new TopicBoundaryDetector(TopicIsolationConfig.defaults());

    @Test
    public void similarInputContinuesTopic() {
        TopicRelation relation = detector.relation(
                "请帮我分析一下PackageRuleDescriptionService的使用情况",
                Arrays.asList("帮我分析一下PackageRuleDescriptionService这个类被那些类使用过"));

        assertEquals(TopicRelation.CONTINUE, relation);
    }

    @Test
    public void unrelatedInputStartsNewTopic() {
        TopicRelation relation = detector.relation(
                "今天天气怎么样",
                Arrays.asList("订单服务异常怎么排查"));

        assertEquals(TopicRelation.SHIFT, relation);
    }

    @Test
    public void commonInstructionPrefixDoesNotKeepOldTopic() {
        TopicRelation relation = detector.relation(
                "请帮我分析一下支付服务",
                Arrays.asList("请帮我分析一下订单服务"));

        assertEquals(TopicRelation.SHIFT, relation);
    }

    @Test
    public void explicitReferenceIsRecognized() {
        TopicRelation relation = detector.relation(
                "继续刚才订单服务的问题",
                Arrays.asList("今天天气怎么样"));

        assertEquals(TopicRelation.EXPLICIT_REFERENCE, relation);
    }

    @Test
    public void switchMarkerWins() {
        TopicRelation relation = detector.relation(
                "换个话题，今天天气怎么样",
                Arrays.asList("订单服务异常怎么排查"));

        assertEquals(TopicRelation.SHIFT, relation);
    }

    @Test
    public void shortContinuationIsAmbiguous() {
        TopicRelation relation = detector.relation(
                "那这个呢",
                Collections.singletonList("订单服务异常怎么排查"));

        assertEquals(TopicRelation.AMBIGUOUS, relation);
    }

    @Test
    public void blankInputDoesNotChangeTopic() {
        assertEquals(TopicRelation.CONTINUE, detector.relation("  ", Collections.<String>emptyList()));
    }
}
