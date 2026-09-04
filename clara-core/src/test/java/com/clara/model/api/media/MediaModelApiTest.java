package com.clara.model.api.media;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.InvocationContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaModelApiTest {
    private final InvocationContext context = new InvocationContext("tenant", "user", "run", "trace",
            Long.MAX_VALUE, new CancellationToken());

    @Test public void imageRequestDistinguishesGenerationEditAndMaskEdit() {
        ImageGenerationRequest generation = new ImageGenerationRequest("draw", null, null, 2,
                Integer.valueOf(512), Integer.valueOf(512), null, "high", null, Long.valueOf(3), null, null, context);
        assertFalse(generation.isEdit());
        ArrayList<String> sources = new ArrayList<String>(Collections.singletonList("source"));
        ImageGenerationRequest edit = new ImageGenerationRequest("edit", sources, "mask", 1,
                null, null, "1:1", null, null, null, null, null, context);
        sources.clear();
        assertTrue(edit.isEdit());
        assertTrue(edit.isMaskEdit());
        assertEquals(1, edit.getSourceImageAssetIds().size());
    }

    @Test public void rejectsAmbiguousImageAndVideoParameters() {
        assertInvalid(new Runnable() { public void run() { new ImageGenerationRequest("draw", null, null, 1,
                Integer.valueOf(10), null, null, null, null, null, null, null, context); }});
        assertInvalid(new Runnable() { public void run() { new ImageGenerationRequest("edit", null, "mask", 1,
                null, null, null, null, null, null, null, null, context); }});
        assertInvalid(new Runnable() { public void run() { new VideoGenerationRequest("video", null, null, "last",
                null, null, null, null, null, null, null, null, context); }});
        assertInvalid(new Runnable() { public void run() { new VideoGenerationRequest("video", null, null, null,
                null, Integer.valueOf(100), Integer.valueOf(100), "1:1", null, null, null, null, context); }});
    }

    @Test public void validatesAudioParametersAndTranscriptionTimeline() {
        assertInvalid(new Runnable() { public void run() { new AudioTranscriptionRequest("asset", null, null,
                Double.valueOf(1.1), null, null, context); }});
        assertInvalid(new Runnable() { public void run() { new SpeechSynthesisRequest("text", null, null,
                "audio/mpeg", null, Double.valueOf(0), null, context); }});
        AudioTranscriptionResult result = new AudioTranscriptionResult("hello", "en", Long.valueOf(1000),
                Arrays.asList(new TranscriptionSegment(0, 400, "hel"), new TranscriptionSegment(400, 1000, "lo")), null);
        assertEquals(2, result.getSegments().size());
        assertInvalid(new Runnable() { public void run() { new AudioTranscriptionResult("hello", null, null,
                Arrays.asList(new TranscriptionSegment(100, 300, "a"), new TranscriptionSegment(200, 400, "b")), null); }});
    }

    @Test public void mediaResultsRequireManagedAssetReferences() {
        assertInvalid(new Runnable() { public void run() { new ImageGenerationResult(Collections.<String>emptyList(), null, null); }});
        assertInvalid(new Runnable() { public void run() { new SpeechSynthesisResult(" ", null, null); }});
    }

    private static void assertInvalid(Runnable action) {
        try { action.run(); fail("expected invalid request"); }
        catch (IllegalArgumentException expected) { }
    }
}
