package com.clara.kernel.http;

import com.clara.context.ConvContext;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the OpenAI image generation body built from provider configuration.
 */
public class HttpRequestOpenAiImageTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void appliesGptImageDefaultsAndOmitsUnsupportedResponseFormat() throws Exception {
        try (ProviderConfiguration provider = loadOpenAiImage()) {
            ConvContext context = new ConvContext(null, "c1", provider, CapabilityKindEnum.IMAGE);
            try (ConvContext.Scope ignored = ConvContext.open(context)) {
                HttpRequest request = HttpRequest.builder()
                        .model(provider.toConfig(CapabilityKindEnum.IMAGE).getModelId())
                        .prompt("A serene koi pond at sunset")
                        .stream(Boolean.FALSE)
                        .build();

                String body = new String(request.toRequest().getBody(), StandardCharsets.UTF_8);

                assertTrue(body, body.contains("\"model\":\"gpt-image-1\""));
                assertTrue(body, body.contains("\"prompt\":\"A serene koi pond at sunset\""));
                assertTrue(body, body.contains("\"size\":\"1024x1024\""));
                assertTrue(body, body.contains("\"n\":1"));
                assertTrue(body, body.contains("\"output_format\":\"png\""));
                assertFalse(body, body.contains("response_format"));
                assertFalse(body, body.contains("\"image\""));
            }
        }
    }

    private ProviderConfiguration loadOpenAiImage() throws Exception {
        Path file = folder.newFile("openai-image.yml").toPath();
        Files.write(file, openAiImageYaml().getBytes(StandardCharsets.UTF_8));
        return new ProviderYamlConfigurationLoader().load(file);
    }

    private static String openAiImageYaml() {
        return "provider: OPENAI\n"
                + "credential:\n"
                + "  secretRef: openai\n"
                + "  apiKey: test-key\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - https://new.aicontrol.top/\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 600000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "  headers:\n"
                + "    x-Transfer: true\n"
                + "capabilities:\n"
                + "  IMAGE:\n"
                + "    endpoint: https://new.aicontrol.top/v1/images/generations\n"
                + "    modelId: gpt-image-1\n"
                + "    apiProfile: images-v1\n"
                + "    parameters:\n"
                + "      maxPromptChars: 12000\n"
                + "      maxInputBytes: 33554432\n"
                + "      maxOutputs: 1\n";
    }
}