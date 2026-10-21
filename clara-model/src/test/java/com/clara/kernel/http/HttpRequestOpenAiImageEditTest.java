package com.clara.kernel.http;

import com.clara.context.ConvContext;
import com.clara.core.transport.RawHttpRequest;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that an OpenAI image request with references is encoded as multipart
 * {@code /v1/images/edits}, while a text-to-image request keeps the JSON generation path.
 */
public class HttpRequestOpenAiImageEditTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void encodesDataUriReferenceAsMultipartEdit() throws Exception {
        try (ProviderConfiguration provider = loadOpenAiImage()) {
            String encoded = Base64.getEncoder().encodeToString("PNGDATA-1234".getBytes(StandardCharsets.UTF_8));
            RawHttpRequest raw = encode(provider, Collections.singletonList("data:image/png;base64," + encoded),
                    "make the background blue");

            assertEquals("https://new.aicontrol.top/v1/images/edits", raw.getEndpoint().toString());
            String contentType = header(raw, "Content-Type");
            assertNotNull(contentType);
            assertTrue(contentType, contentType.startsWith("multipart/form-data; boundary="));

            String body = new String(raw.getBody(), StandardCharsets.UTF_8);
            assertTrue(body, body.contains("name=\"model\""));
            assertTrue(body, body.contains("gpt-image-2"));
            assertTrue(body, body.contains("name=\"prompt\""));
            assertTrue(body, body.contains("make the background blue"));
            assertTrue(body, body.contains("name=\"image\"; filename=\"reference-1.png\""));
            assertTrue(body, body.contains("Content-Type: image/png"));
            assertTrue(body, body.contains("PNGDATA-1234"));
            assertFalse(body, body.contains("\"image\":"));
        }
    }

    @Test
    public void encodesLocalFileReferenceWithItsOwnName() throws Exception {
        Path image = folder.newFile("photo.jpg").toPath();
        Files.write(image, "JPEGDATA-9876".getBytes(StandardCharsets.UTF_8));

        try (ProviderConfiguration provider = loadOpenAiImage()) {
            RawHttpRequest raw = encode(provider, Collections.singletonList(image.toString()), "restyle");

            String body = new String(raw.getBody(), StandardCharsets.UTF_8);
            assertTrue(body, body.contains("name=\"image\"; filename=\"photo.jpg\""));
            assertTrue(body, body.contains("Content-Type: image/jpeg"));
            assertTrue(body, body.contains("JPEGDATA-9876"));
        }
    }

    @Test
    public void keepsJsonGenerationWhenNoReferenceIsGiven() throws Exception {
        try (ProviderConfiguration provider = loadOpenAiImage()) {
            RawHttpRequest raw = encode(provider, null, "a serene pond");

            assertEquals("https://new.aicontrol.top/v1/images/generations", raw.getEndpoint().toString());
            String contentType = header(raw, "Content-Type");
            assertNotNull(contentType);
            assertTrue(contentType, contentType.startsWith("application/json"));
            String body = new String(raw.getBody(), StandardCharsets.UTF_8);
            assertTrue(body, body.startsWith("{"));
            assertFalse(body, body.contains("\"image\":"));
        }
    }

    private static RawHttpRequest encode(ProviderConfiguration provider, java.util.List<String> images, String prompt) {
        ConvContext context = new ConvContext(null, "c1", provider, CapabilityKindEnum.IMAGE);
        try (ConvContext.Scope ignored = ConvContext.open(context)) {
            HttpRequest request = HttpRequest.builder()
                    .model(provider.toConfig(CapabilityKindEnum.IMAGE).getModelId())
                    .prompt(prompt)
                    .image(images)
                    .stream(Boolean.FALSE)
                    .build();
            return request.toRequest();
        }
    }

    private static String header(RawHttpRequest request, String name) {
        for (java.util.Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private ProviderConfiguration loadOpenAiImage() throws Exception {
        Path file = folder.newFile("openai-image-edit.yml").toPath();
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
                + "  maxRequestBytes: 33554432\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  IMAGE:\n"
                + "    endpoint: https://new.aicontrol.top/v1/images/generations\n"
                + "    modelId: gpt-image-2\n"
                + "    apiProfile: images-v1\n"
                + "    parameters:\n"
                + "      maxPromptChars: 12000\n"
                + "      maxInputBytes: 33554432\n"
                + "      maxOutputs: 1\n"
                + "      maxSourceImages: 16\n";
    }
}
