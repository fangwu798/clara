package com.clara.model.api;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ErrorCategory;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamEventTest {
    @Test public void preservesLegacyPayloadConstructor() {
        StreamEvent event = new StreamEvent(StreamEvent.Type.TEXT_DELTA, 1L, "hello");
        assertEquals(StreamEvent.Type.TEXT_DELTA, event.getType());
        assertEquals(1L, event.getSequence());
        assertEquals("hello", event.getPayload());
        assertNull(event.getToolCallId());
        assertNull(event.getResponse());
        assertNull(event.getError());
    }

    @Test public void carriesStructuredToolAndErrorFields() {
        ClaraError error = new ClaraError("MODEL_TEST", ErrorCategory.EXTERNAL_SERVICE,
                "MODEL_TEST", false, null, "OPENAI", null, null,
                Collections.<String, String>emptyMap());
        StreamEvent event = new StreamEvent(StreamEvent.Type.ERROR, 2L, "MODEL_TEST",
                "call-1", "lookup", null, error);
        assertEquals("call-1", event.getToolCallId());
        assertEquals("lookup", event.getToolName());
        assertSame(error, event.getError());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeSequence() {
        new StreamEvent(StreamEvent.Type.ERROR, -1L, "error");
    }
}
