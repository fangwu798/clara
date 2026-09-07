package com.clara.model.configuration;

import com.clara.model.api.ModelProvider;
import com.clara.model.provider.compatible.OpenAiCompatibleChatConfig;
import com.clara.model.provider.compatible.OpenAiCompatibleEmbeddingConfig;
import com.clara.model.provider.http.ProviderCredentialResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class ProviderYamlConfigurationLoaderTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void loadsTypedConfigurationAndResolvesInlineLocalCredential() throws Exception {
        ProviderConfiguration configuration = load(validYaml());
        try {
            assertEquals(ModelProvider.OPENAI, configuration.getProvider());
            assertEquals("https://api.example.com:443",
                    configuration.getTransport().getAllowedOrigins().iterator().next());

            OpenAiCompatibleChatConfig chat =
                    configuration.toOpenAiCompatibleChatConfig("chat");
            assertEquals("chat-model", chat.getModelId());
            assertEquals("local-secret", chat.getSecretRef());

            OpenAiCompatibleEmbeddingConfig embedding =
                    configuration.toOpenAiCompatibleEmbeddingConfig("embedding");
            assertEquals(1536, embedding.getDimension());
            assertFalse(configuration.getTransport().isAllowHttp());

            ProviderCredentialResolver resolver = configuration.credentialResolver();
            assertArrayEquals("test-key".toCharArray(),
                    resolver.resolve("tenant", "local-secret"));
            assertEquals(null, resolver.resolve("tenant", "other-secret"));
        } finally {
            configuration.close();
        }
    }

    @Test
    public void rejectsUnknownFieldsAndDuplicateKeys() throws Exception {
        assertInvalid(validYaml() + "unknown: value\n", "unknown field");
        assertInvalid(validYaml().replace("provider: OPENAI",
                "provider: OPENAI\nprovider: DEEPSEEK"), "cannot read");
    }

    @Test
    public void rejectsMultipleDocumentsAndFractionalIntegerLimits() throws Exception {
        assertInvalid(validYaml() + "---\nprovider: OPENAI\n", "exactly one YAML document");
        ProviderConfiguration configuration = load(
                validYaml().replace("dimension: 1536", "dimension: 1536.5"));
        try {
            configuration.toOpenAiCompatibleEmbeddingConfig("embedding");
            fail("expected integer parameter validation failure");
        } catch (IllegalArgumentException expected) {
            assertEquals("dimension must be an integer", expected.getMessage());
        } finally {
            configuration.close();
        }
    }

    @Test
    public void rejectsOversizedAndNonYamlFiles() throws Exception {
        Path text = temporaryFolder.newFile("provider.txt").toPath();
        Files.write(text, validYaml().getBytes(StandardCharsets.UTF_8));
        try {
            new ProviderYamlConfigurationLoader().load(text);
            fail("expected extension validation failure");
        } catch (IllegalArgumentException expected) {
            assertEquals("configuration file must use .yml or .yaml", expected.getMessage());
        }

        Path yaml = temporaryFolder.newFile("large.yml").toPath();
        Files.write(yaml, validYaml().getBytes(StandardCharsets.UTF_8));
        try {
            new ProviderYamlConfigurationLoader(10L).load(yaml);
            fail("expected size validation failure");
        } catch (IllegalArgumentException expected) {
            assertEquals("configuration file exceeds size limit", expected.getMessage());
        }
    }

    @Test
    public void repositoryContainsOneValidExampleForEveryProvider() throws Exception {
        Path classes = Paths.get(ProviderYamlConfigurationLoaderTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path examples = classes.getParent().getParent().getParent()
                .resolve("config").resolve("model-providers").resolve("examples");
        Set<ModelProvider> providers = EnumSet.noneOf(ModelProvider.class);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(examples, "*.yml")) {
            for (Path file : files) {
                ProviderConfiguration configuration =
                        new ProviderYamlConfigurationLoader().load(file);
                try {
                    if (!providers.add(configuration.getProvider())) {
                        fail("duplicate provider example: " + configuration.getProvider());
                    }
                } finally {
                    configuration.close();
                }
            }
        }
        assertEquals(EnumSet.allOf(ModelProvider.class), providers);
    }

    private ProviderConfiguration load(String yaml) throws Exception {
        Path file = temporaryFolder.newFile("provider-" + System.nanoTime() + ".yml").toPath();
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return new ProviderYamlConfigurationLoader().load(file);
    }

    private void assertInvalid(String yaml, String expectedMessagePart) throws Exception {
        try {
            ProviderConfiguration configuration = load(yaml);
            configuration.close();
            fail("expected invalid YAML configuration");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains(expectedMessagePart)) {
                throw expected;
            }
        }
    }

    private static String validYaml() {
        return "provider: OPENAI\n"
                + "credential:\n"
                + "  secretRef: local-secret\n"
                + "  apiKey: test-key\n"
                + "transport:\n"
                + "  allowedOrigins: [https://api.example.com]\n"
                + "  connectTimeoutMillis: 1000\n"
                + "  readTimeoutMillis: 2000\n"
                + "  maxRequestBytes: 4096\n"
                + "  maxResponseBytes: 8192\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  chat:\n"
                + "    endpoint: https://api.example.com/v1/chat/completions\n"
                + "    modelId: chat-model\n"
                + "    apiProfile: chat-v1\n"
                + "  embedding:\n"
                + "    endpoint: https://api.example.com/v1/embeddings\n"
                + "    modelId: embedding-model\n"
                + "    apiProfile: embedding-v1\n"
                + "    parameters:\n"
                + "      dimension: 1536\n"
                + "      maxInputs: 16\n"
                + "      maxTextChars: 8192\n"
                + "      maxTotalChars: 32768\n";
    }
}
