package com.clara.model.runtime;

import com.clara.model.api.ModelParameters;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ModelParameterOverlayTest {
    private final ModelParameterOverlay overlay = new ModelParameterOverlay();

    @Test
    public void replacesOnlyExplicitValues() {
        ModelParameters lower = new ModelParameters("base", 0.2, 0.8, 100,
                Arrays.asList("END"), false);
        ModelParameters higher = new ModelParameters(null, 0.6, null, null,
                Collections.<String>emptyList(), false);
        ModelParameters merged = overlay.merge(lower, higher);
        assertEquals("base", merged.getModel());
        assertEquals(Double.valueOf(0.6), merged.getTemperature());
        assertEquals(Integer.valueOf(100), merged.getMaxTokens());
        assertEquals(Arrays.asList("END"), merged.getStops());
    }

    @Test
    public void clearsStopsOnlyWhenExplicitlyRequested() {
        ModelParameters lower = new ModelParameters("base", null, null, null,
                Arrays.asList("END"), false);
        ModelParameters higher = new ModelParameters(null, null, null, null,
                Collections.<String>emptyList(), true);
        assertTrue(overlay.merge(lower, higher).getStops().isEmpty());
    }

    @Test
    public void overlaysAllExplicitScalarParameters() {
        ModelParameters lower = new ModelParameters("base", 0.2, 0.8, 20, 100,
                0.1, 0.2, "text", Arrays.asList("END"), false);
        ModelParameters higher = new ModelParameters(null, null, null, 40, null,
                0.3, null, "json", Collections.<String>emptyList(), false);

        ModelParameters merged = overlay.merge(lower, higher);

        assertEquals(Integer.valueOf(40), merged.getTopK());
        assertEquals(Double.valueOf(0.3), merged.getFrequencyPenalty());
        assertEquals(Double.valueOf(0.2), merged.getPresencePenalty());
        assertEquals("json", merged.getResponseFormat());
    }
}
