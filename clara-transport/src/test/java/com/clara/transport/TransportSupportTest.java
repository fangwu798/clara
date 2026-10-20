package com.clara.transport;

import com.clara.core.transport.IExecutionGuard;
import com.clara.exception.HttpTransportException;
import com.clara.transport.concurrent.DaemonThreads;
import com.clara.transport.http.DeadlineGuard;
import com.clara.transport.http.HostEndpointPolicy;
import com.clara.transport.http.OriginEndpointPolicy;
import com.clara.transport.io.BoundedStreams;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ThreadFactory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 传输层公共组件的分支测试：截止守卫、源站 / 主机白名单、守护线程工厂与限长流读取。
 */
public class TransportSupportTest {

    @Test
    public void deadlineGuardTracksDeadlineAndCancellation() {
        DeadlineGuard guard = DeadlineGuard.after(5_000L);
        assertFalse(guard.isCancelled());
        assertTrue(guard.remainingMillis() > 0L);
        guard.cancel();
        assertTrue(guard.isCancelled());

        DeadlineGuard unlimited = DeadlineGuard.withoutDeadline();
        assertEquals(Long.MAX_VALUE, unlimited.remainingMillis());
        unlimited.check();

        assertIllegalArgument(() -> DeadlineGuard.after(0L));
        assertIllegalArgument(() -> DeadlineGuard.after(-1L));
    }

    @Test
    public void expiredDeadlineFailsCheck() throws Exception {
        DeadlineGuard guard = DeadlineGuard.after(1L);
        Thread.sleep(5L);
        try {
            guard.check();
            fail("expected timeout");
        } catch (HttpTransportException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }

    @Test
    public void originPolicyMatchesSchemeHostAndPort() {
        OriginEndpointPolicy policy = new OriginEndpointPolicy(
                Arrays.asList("https://api.example.com", "http://127.0.0.1:8080"), true);

        assertTrue(policy.allows(URI.create("https://api.example.com/v1/chat")));
        assertFalse(policy.allows(URI.create("https://other.example.com/v1/chat")));
        assertTrue(policy.allows(URI.create("http://127.0.0.1:8080/mcp")));
        assertFalse(policy.allows(URI.create("/relative")));
        assertFalse(policy.allows(null));
    }

    @Test
    public void originPolicyRejectsHttpWhenDisabled() {
        OriginEndpointPolicy policy = new OriginEndpointPolicy(
                Collections.singletonList("https://api.example.com"), false);

        assertTrue(policy.allows(URI.create("https://api.example.com/x")));
        assertFalse(policy.allows(URI.create("http://api.example.com/x")));
        assertIllegalArgument(() -> new OriginEndpointPolicy(Collections.<String>emptyList(), true));
        assertIllegalArgument(() -> new OriginEndpointPolicy(Arrays.asList("https://a", null), true));
    }

    @Test
    public void hostPolicyMatchesHostOnly() {
        HostEndpointPolicy policy = new HostEndpointPolicy(Arrays.asList("API.example.com", " localhost "), false);

        assertTrue(policy.allows(URI.create("https://api.example.com/v1")));
        assertFalse(policy.allows(URI.create("https://other.example.com/v1")));
        assertFalse(policy.allows(URI.create("/relative")));
        assertFalse(policy.allows(null));
        assertIllegalArgument(() -> new HostEndpointPolicy(Collections.<String>emptyList(), true));
        assertIllegalArgument(() -> new HostEndpointPolicy(Arrays.asList("  "), true));
    }

    @Test
    public void daemonThreadFactoryNamesAndMarksThreads() {
        ThreadFactory factory = DaemonThreads.daemonThreadFactory("probe-");
        Thread first = factory.newThread(() -> { });
        Thread second = factory.newThread(() -> { });

        assertEquals("probe-1", first.getName());
        assertEquals("probe-2", second.getName());
        assertTrue(first.isDaemon());
        assertTrue(second.isDaemon());
    }

    @Test
    public void readsBoundedLines() throws Exception {
        BufferedReader reader = new BufferedReader(new StringReader("alpha\r\nbeta\nlast"));

        assertEquals("alpha", BoundedStreams.readBoundedLine(reader, 100));
        assertEquals("beta", BoundedStreams.readBoundedLine(reader, 100));
        assertEquals("last", BoundedStreams.readBoundedLine(reader, 100));
        assertNull(BoundedStreams.readBoundedLine(reader, 100));
    }

    @Test
    public void rejectsOverlongLinesByCharsAndBytes() {
        try {
            BoundedStreams.readBoundedLine(new BufferedReader(new StringReader("abcdef\n")), 3);
            fail("expected too large");
        } catch (Exception expected) {
            assertTrue(expected instanceof HttpTransportException || expected.getCause() instanceof HttpTransportException);
        }
        try {
            BoundedStreams.readBoundedLine(new BufferedReader(new StringReader("中文\n")), 3);
            fail("expected too large");
        } catch (Exception expected) {
            assertTrue(expected instanceof HttpTransportException || expected.getCause() instanceof HttpTransportException);
        }
    }

    @Test
    public void readAllHonoursLimitsCancellationAndNullInput() throws Exception {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(new byte[0], BoundedStreams.readAll(null, 10, IExecutionGuard.NONE));
        assertArrayEquals(payload, BoundedStreams.readAll(new ByteArrayInputStream(payload), 10, IExecutionGuard.NONE));

        try {
            BoundedStreams.readAll(new ByteArrayInputStream(payload), 3, IExecutionGuard.NONE);
            fail("expected too large");
        } catch (HttpTransportException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }

        IExecutionGuard cancelled = new IExecutionGuard() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public long remainingMillis() {
                return 1000L;
            }
        };
        try {
            BoundedStreams.readAll(new ByteArrayInputStream(payload), 10, cancelled);
            fail("expected cancelled");
        } catch (HttpTransportException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}