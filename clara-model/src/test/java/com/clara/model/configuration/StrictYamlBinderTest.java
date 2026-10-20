package com.clara.model.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Strict YAML binding: shape checks, unknown keys, duplicates and error translation.
 */
public class StrictYamlBinderTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void bindsValidYamlCaseInsensitively() throws IOException {
        File file = yaml("sample.yml", "name: alpha\ncount: 3\nmode: FAST\n");
        Sample sample = new StrictYamlBinder().bind(file.toPath(), Sample.class);
        assertEquals("alpha", sample.name);
        assertEquals(3, sample.count);
        assertEquals(Mode.FAST, sample.mode);
    }

    @Test
    public void acceptsYamlExtensionWithUpperCase() throws IOException {
        File file = yaml("sample.YAML", "name: alpha\ncount: 1\n");
        assertEquals("alpha", new StrictYamlBinder().bind(file.toPath(), Sample.class).name);
    }

    @Test
    public void rejectsNullArguments() {
        assertIllegalArgument(() -> new StrictYamlBinder().bind(null, Sample.class));
        assertIllegalArgument(() -> new StrictYamlBinder().bind(folder.getRoot().toPath(), null));
        assertIllegalArgument(() -> new StrictYamlBinder(0L));
    }

    @Test
    public void rejectsWrongSuffixAndMissingFile() throws IOException {
        File file = yaml("sample.txt", "name: alpha\ncount: 1\n");
        assertIllegalArgument(() -> new StrictYamlBinder().bind(file.toPath(), Sample.class));
        assertIllegalArgument(() -> new StrictYamlBinder().bind(
                folder.getRoot().toPath().resolve("missing.yml"), Sample.class));
    }

    @Test
    public void rejectsDirectoryAndOversizedFile() throws IOException {
        Path directory = folder.newFolder("dir.yml").toPath();
        assertIllegalArgument(() -> new StrictYamlBinder().bind(directory, Sample.class));

        File file = yaml("big.yml", "name: " + repeat('x', 100) + "\ncount: 1\n");
        assertIllegalArgument(() -> new StrictYamlBinder(10L).bind(file.toPath(), Sample.class));
    }

    @Test
    public void rejectsUnknownPropertyWithPath() throws IOException {
        File file = yaml("unknown.yml", "name: a\ncount: 1\nextra: true\n");
        try {
            new StrictYamlBinder().bind(file.toPath(), Sample.class);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("extra"));
        }
    }

    @Test
    public void rejectsDuplicateKeys() throws IOException {
        File file = yaml("duplicate.yml", "name: a\nname: b\ncount: 1\n");
        assertIllegalArgument(() -> new StrictYamlBinder().bind(file.toPath(), Sample.class));
    }

    @Test
    public void rejectsWrongTypeAndMissingField() throws IOException {
        File wrongType = yaml("wrong.yml", "name: a\ncount: not-a-number\n");
        assertIllegalArgument(() -> new StrictYamlBinder().bind(wrongType.toPath(), Sample.class));

        File missing = yaml("missing-field.yml", "name: a\n");
        assertIllegalArgument(() -> new StrictYamlBinder().bind(missing.toPath(), RequiredSample.class));
    }

    @Test
    public void surfacesConstructorValidationMessage() throws IOException {
        File file = yaml("invalid.yml", "name: a\ncount: -1\n");
        try {
            new StrictYamlBinder().bind(file.toPath(), Validated.class);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("count must not be negative"));
        }
    }

    @Test
    public void rejectsEmptyDocumentAndTrailingDocument() throws IOException {
        File empty = yaml("empty.yml", "null\n");
        assertIllegalArgument(() -> new StrictYamlBinder().bind(empty.toPath(), Sample.class));

        File trailing = yaml("trailing.yml", "name: a\ncount: 1\n---\nname: b\ncount: 2\n");
        assertIllegalArgument(() -> new StrictYamlBinder().bind(trailing.toPath(), Sample.class));
    }

    private File yaml(String name, String content) throws IOException {
        File file = folder.newFile(name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String repeat(char value, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    public enum Mode {
        FAST, SLOW
    }

    public static class Sample {
        public String name;
        public int count;
        public Mode mode;
    }

    public static class RequiredSample {
        public final String name;
        public final int count;

        @JsonCreator
        public RequiredSample(@JsonProperty(value = "name", required = true) String name,
                              @JsonProperty(value = "count", required = true) int count) {
            this.name = name;
            this.count = count;
        }
    }

    public static class Validated {
        public final String name;
        public final int count;

        @JsonCreator
        public Validated(@JsonProperty("name") String name, @JsonProperty("count") int count) {
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
            this.name = name;
            this.count = count;
        }
    }
}
