package com.clara.kernel;

import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class InvocationContextTest {
    @Test
    public void childSharesCancellationAndCannotExtendDeadline() {
        CancellationToken token = new CancellationToken();
        InvocationContext parent = new InvocationContext(
                "tenant", "user", "run", "instance", "trace", 1000L, token);
        InvocationContext child = parent.child("child-run", "child-instance", 900L);

        assertSame(token, child.getCancellationToken());
        token.cancel();
        assertTrue(child.getCancellationToken().isCancellationRequested());
    }

    @Test(expected = IllegalArgumentException.class)
    public void childCannotExtendDeadline() {
        InvocationContext parent = new InvocationContext(
                "tenant", null, null, "trace", 1000L, new CancellationToken());
        parent.child(null, null, 1001L);
    }
}
