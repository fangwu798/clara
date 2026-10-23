package com.clara.interaction;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 执行期补充缓冲区：顺序、空值过滤、取走即清空与并发写入。
 */
public class UserInterventionBufferTest {

    @Test
    public void keepsOrderAndFiltersBlankValues() {
        UserInterventionBuffer buffer = new UserInterventionBuffer();
        buffer.offer(" first ");
        buffer.offer("   ");
        buffer.offer(null);
        buffer.offer("second");

        assertTrue(buffer.hasPending());
        assertEquals(Arrays.asList("first", "second"), buffer.drain());
        assertFalse(buffer.hasPending());
        assertTrue(buffer.drain().isEmpty());
    }

    @Test
    public void acceptsConcurrentOffersWithoutLosingItems() throws Exception {
        final UserInterventionBuffer buffer = new UserInterventionBuffer();
        Thread[] workers = new Thread[4];
        for (int worker = 0; worker < workers.length; worker++) {
            final int workerIndex = worker;
            workers[worker] = new Thread(() -> {
                for (int item = 0; item < 100; item++) {
                    buffer.offer("t" + workerIndex + "-" + item);
                }
            });
            workers[worker].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        List<String> drained = buffer.drain();
        Set<String> unique = new HashSet<String>(drained);
        assertEquals(400, drained.size());
        assertEquals(400, unique.size());
    }
}
