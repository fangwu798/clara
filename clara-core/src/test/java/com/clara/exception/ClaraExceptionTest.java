package com.clara.exception;

import com.clara.enums.ErrorCategoryEnum;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.Assert.assertEquals;

public class ClaraExceptionTest {

    @Test
    public void serializationKeepsStructuredError() throws Exception {
        ClaraException original = new ClaraException(
                ClaraError.builder("TEST_ERROR", ErrorCategoryEnum.VALIDATION)
                        .safeMessage("boom")
                        .retryable(true)
                        .build());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        ClaraException restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (ClaraException) input.readObject();
        }

        assertEquals("TEST_ERROR", restored.getError().getCode());
        assertEquals(ErrorCategoryEnum.VALIDATION, restored.getError().getCategory());
        assertEquals("boom", restored.getMessage());
    }
}
