package com.clara.orchestration.tool;

import com.clara.dto.model.StreamEvent;
import com.clara.enums.StreamEventEnum;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link FirstTokenObserver} 的首字符计时测试：只认第一个非空内容片段，并保持事件完整转发。
 */
public class FirstTokenObserverTest {

    @Test
    public void recordsFirstTextDelta() {
        FirstTokenObserver observer = new FirstTokenObserver(null);
        long started = System.nanoTime();

        observer.onEvent(new StreamEvent(StreamEventEnum.TEXT_DELTA, 0L, "你"));
        observer.onEvent(new StreamEvent(StreamEventEnum.TEXT_DELTA, 1L, "好"));

        Long firstToken = observer.firstTokenMillis(started);
        assertNotNull(firstToken);
        assertTrue(firstToken >= 0L);
    }

    @Test
    public void ignoresEmptyPayload() {
        FirstTokenObserver observer = new FirstTokenObserver(null);
        long started = System.nanoTime();

        observer.onEvent(new StreamEvent(StreamEventEnum.TEXT_DELTA, 0L, ""));
        observer.onEvent(new StreamEvent(StreamEventEnum.REASONING_DELTA, 1L, null));

        assertNull(observer.firstTokenMillis(started));
    }

    @Test
    public void toolArgumentDeltaCountsAsFirstOutput() {
        FirstTokenObserver observer = new FirstTokenObserver(null);
        long started = System.nanoTime();

        observer.onEvent(new StreamEvent(StreamEventEnum.TEXT_DELTA, 0L, ""));
        observer.onEvent(new StreamEvent(StreamEventEnum.TOOL_ARGUMENT_DELTA, 1L, "{\"path\""));

        assertNotNull(observer.firstTokenMillis(started));
    }

    @Test
    public void forwardsEveryEventToDelegate() {
        List<StreamEvent> received = new ArrayList<>();
        FirstTokenObserver observer = new FirstTokenObserver(received::add);

        StreamEvent delta = new StreamEvent(StreamEventEnum.TEXT_DELTA, 0L, "a");
        StreamEvent complete = new StreamEvent(StreamEventEnum.RESPONSE_COMPLETE, 1L, "");
        observer.onEvent(delta);
        observer.onEvent(complete);

        assertEquals(2, received.size());
        assertEquals(delta, received.get(0));
        assertEquals(complete, received.get(1));
    }
}