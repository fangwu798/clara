package com.clara.model.runtime;

import com.clara.model.api.ModelCapability;
import com.clara.model.api.ModelProvider;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;

public class ModelRegistryTest {
    @Test
    public void keysModelsByProviderAndAlias() {
        ModelRegistry registry = new ModelRegistry();
        RegisteredModel openai = model(ModelProvider.OPENAI, "shared");
        RegisteredModel deepseek = model(ModelProvider.DEEPSEEK, "shared");
        registry.register(openai);
        registry.register(deepseek);
        assertSame(openai, registry.get(ModelProvider.OPENAI, "shared"));
        assertSame(deepseek, registry.get(ModelProvider.DEEPSEEK, "shared"));
    }

    @Test
    public void reportsStableCodeForMissingModel() {
        try {
            new ModelRegistry().get(ModelProvider.OPENAI, "missing");
            fail("expected ClaraException");
        } catch (ClaraException exception) {
            assertEquals(ErrorCodes.MODEL_NOT_REGISTERED, exception.getError().getCode());
        }
    }

    private RegisteredModel model(ModelProvider provider, String alias) {
        ProviderModelDescriptor descriptor = new ProviderModelDescriptor(provider,
                alias + "-id", alias, "chat", "global", "v1",
                Collections.<ModelCapability>emptySet());
        return new RegisteredModel(descriptor, null, null, null);
    }
}
