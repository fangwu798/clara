package com.clara.model.runtime;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.model.api.ModelCapability;
import com.clara.model.api.ModelProvider;
import com.clara.kernel.CancellationToken;
import com.clara.kernel.InvocationContext;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.api.media.VideoGenerationRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ModelCapabilityGuardTest {
    @Test
    public void rejectsUnsupportedCapabilityWithStableCode() {
        ProviderModelDescriptor descriptor = new ProviderModelDescriptor(
                ModelProvider.DEEPSEEK, "deepseek-chat", "chat", "chat", "global", "v1",
                EnumSet.of(ModelCapability.CHAT_TEXT));
        try {
            new ModelCapabilityGuard().require(descriptor, ModelCapability.IMAGE_GENERATION);
            fail("expected ClaraException");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.CAPABILITY_NOT_SUPPORTED, exception.getError().getCode());
        }
    }

    @Test
    public void rejectsSilentMediaCapabilityDegradation() {
        ProviderModelDescriptor imageOnly = new ProviderModelDescriptor(
                ModelProvider.OPENAI, "image", "image", "images", "global", "v1",
                EnumSet.of(ModelCapability.IMAGE_GENERATION));
        final ImageGenerationRequest edit = new ImageGenerationRequest("edit", Collections.singletonList("source"),
                null, 1, null, null, null, null, null, null, null, null, context());
        assertUnsupported(new Runnable() { public void run() { new ModelCapabilityGuard().require(imageOnly, edit); }});

        ProviderModelDescriptor videoOnly = new ProviderModelDescriptor(
                ModelProvider.VOLCENGINE, "video", "video", "jobs", "cn", "v1",
                EnumSet.of(ModelCapability.VIDEO_GENERATION));
        final VideoGenerationRequest referenced = new VideoGenerationRequest("video", Arrays.asList("reference"),
                null, null, null, null, null, null, null, null, null, null, context());
        assertUnsupported(new Runnable() { public void run() { new ModelCapabilityGuard().require(videoOnly, referenced); }});
    }

    private static InvocationContext context() {
        return new InvocationContext("tenant", null, "run", "trace", Long.MAX_VALUE, new CancellationToken());
    }
    private static void assertUnsupported(Runnable action) {
        try { action.run(); fail("expected unsupported capability"); }
        catch (ClaraException exception) { assertEquals(ErrorCodes.CAPABILITY_NOT_SUPPORTED, exception.getError().getCode()); }
    }
}
