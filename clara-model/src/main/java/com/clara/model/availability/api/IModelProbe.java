package com.clara.model.availability.api;

import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.availability.ModelCheckResult;

import java.nio.file.Path;

/**
 * Checks whether one configured chat model can produce streamed text.
 */
public interface IModelProbe {

    ModelCheckResult probe(Path path, String alias, ProviderConfiguration provider, ModelConfig chat, long timeoutMillis);
}
