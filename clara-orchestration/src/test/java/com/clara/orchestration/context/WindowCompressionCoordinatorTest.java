package com.clara.orchestration.context;

import com.clara.core.memory.ISummarizeCoordinator;
import com.clara.core.message.IContent;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * {@link WindowCompressionCoordinator} 单元测试：未配置压缩实现或无淘汰时返回 null，否则委托压缩实现。
 */
public class WindowCompressionCoordinatorTest {

    @Test
    public void summarizeReturnsNullWhenCoordinatorMissing() {
        WindowCompressionCoordinator coordinator = new WindowCompressionCoordinator(null);
        assertNull(coordinator.summarize(Collections.<IContent>singletonList(new UserContent("a"))));
    }

    @Test
    public void summarizeReturnsNullForNullOrEmptyEvicted() {
        WindowCompressionCoordinator coordinator =
                new WindowCompressionCoordinator(evicted -> new SystemContent("summary"));

        assertNull(coordinator.summarize(null));
        assertNull(coordinator.summarize(Collections.<IContent>emptyList()));
    }

    @Test
    public void summarizeDelegatesToCoordinatorWhenEvictedPresent() {
        IContent summary = new SystemContent("compressed");
        List<List<IContent>> captured = new ArrayList<List<IContent>>();
        ISummarizeCoordinator delegate = evicted -> {
            captured.add(evicted);
            return summary;
        };
        WindowCompressionCoordinator coordinator = new WindowCompressionCoordinator(delegate);

        List<IContent> evicted = Collections.<IContent>singletonList(new UserContent("old"));
        IContent result = coordinator.summarize(evicted);

        assertSame(summary, result);
        assertEquals(1, captured.size());
        assertSame(evicted, captured.get(0));
    }
}
