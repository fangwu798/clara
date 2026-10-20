package com.clara.context;

import com.clara.exception.ClaraException;
import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Thread binding, call history and cancellation behaviour of the conversation context.
 */
public class ConvContextTest {

    @After
    public void clear() {
        ConvContext.clear();
    }

    @Test
    public void rejectsInvalidConstructionArguments() {
        assertIllegalArgument(() -> new ConvContext(null, null, null, null, null));
        assertIllegalArgument(() -> new ConvContext(null, "  ", null, null, null));
        assertIllegalArgument(() -> new ConvContext(null, "c", null, null, null, null));
    }

    @Test
    public void copiesHistoryAndReturnsLatestCall() {
        CallContext first = new CallContext("first", 1L, new CancellationToken());
        ConvContext context = new ConvContext("u", "c", Collections.singletonMap("first", first), null, null);

        assertSame(first, context.currentCall());
        CallContext second = context.newCall(5000L);
        assertSame(second, context.currentCall());
        assertTrue(context.getHistoryCall().containsKey(second.getTraceId()));

        CallContext third = context.newCall(1L);
        assertSame(third, context.currentCall());
        assertEquals(3, context.getHistoryCall().size());
    }

    @Test
    public void returnsNullWhenNoCallIsRegistered() {
        ConvContext context = new ConvContext(null, "c", null, null, null);
        assertNull(context.currentCall());
    }

    @Test
    public void rejectsNonPositiveTimeout() {
        ConvContext context = new ConvContext(null, "c", null, null, null);
        try {
            context.newCall(0L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void bindsAndRestoresNestedScopes() {
        ConvContext outer = new ConvContext(null, "outer", null, null, null);
        ConvContext inner = new ConvContext(null, "inner", null, null, null);
        assertNull(ConvContext.current());

        try (ConvContext.Scope ignored = ConvContext.open(outer)) {
            assertSame(outer, ConvContext.current());
            try (ConvContext.Scope nested = ConvContext.open(inner)) {
                assertSame(inner, ConvContext.current());
            }
            assertSame(outer, ConvContext.current());
        }
        assertNull(ConvContext.current());

        ConvContext.bind(outer);
        assertSame(outer, ConvContext.current());
        ConvContext.Scope scope = ConvContext.open(inner);
        scope.close();
        scope.close();
        assertSame(outer, ConvContext.current());
    }

    @Test
    public void runWithAndCallWithExposeContextOnlyInsideBlock() {
        ConvContext context = new ConvContext(null, "c", null, null, null);
        AtomicReference<ConvContext> seen = new AtomicReference<>();

        ConvContext.runWith(context, () -> seen.set(ConvContext.current()));
        assertSame(context, seen.get());
        assertNull(ConvContext.current());

        ConvContext returned = ConvContext.callWith(context, ConvContext::current);
        assertSame(context, returned);
        assertNull(ConvContext.current());
    }

    @Test
    public void rejectsNullScopeAndActions() {
        assertIllegalArgument(() -> ConvContext.open(null));
        assertIllegalArgument(() -> ConvContext.bind(null));
        assertIllegalArgument(() -> ConvContext.runWith(null, () -> { }));
        assertIllegalArgument(() -> ConvContext.runWith(new ConvContext(null, "c", null, null, null), null));
        assertIllegalArgument(() -> ConvContext.callWith(null, () -> 1));
        assertIllegalArgument(() -> ConvContext.callWith(new ConvContext(null, "c", null, null, null), null));
    }

    @Test
    public void cancellationIsIdempotentAndThrowsAtCheckpoints() {
        ConvContext context = new ConvContext(null, "c", null, null, null);
        assertFalse(context.isCancelled());
        assertTrue(context.cancel());
        assertTrue(context.isCancelled());
        assertFalse(context.cancel());

        try {
            context.throwIfCancelled();
            fail("expected ClaraException");
        } catch (ClaraException expected) {
            assertNotNull(expected.getError());
            assertEquals("c", expected.getError().getTraceId());
        }
    }

    @Test
    public void cancellationUsesLatestTraceId() {
        ConvContext context = new ConvContext(null, "c", null, null, null);
        CallContext call = context.newCall(1000L);
        context.cancel();
        try {
            context.throwIfCancelled();
            fail("expected ClaraException");
        } catch (ClaraException expected) {
            assertEquals(call.getTraceId(), expected.getError().getTraceId());
        }
    }

    @Test
    public void exposesModelFields() {
        CancellationToken token = new CancellationToken();
        ConvContext context = new ConvContext("user", "conv", null, null,
                com.clara.enums.CapabilityKindEnum.CHAT, token);
        assertEquals("user", context.getUserId());
        assertEquals("conv", context.getConvId());
        assertEquals(com.clara.enums.CapabilityKindEnum.CHAT, context.getCapabilityKind());
        assertSame(token, context.getCancellationToken());
        assertNull(context.getProviderConfiguration());
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
