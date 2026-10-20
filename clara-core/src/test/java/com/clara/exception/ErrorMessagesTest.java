package com.clara.exception;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 错误码到中文提示的翻译规则：已登记取文案、未登记原样返回、占位符单遍替换。
 */
public class ErrorMessagesTest {

    /**
     * 已登记的错误码返回中文提示，而不是错误码本身。
     */
    @Test
    public void translatesRegisteredCode() {
        String message = ErrorMessages.of(ErrorCodes.MODEL_NOT_REGISTERED);
        assertNotNull(message);
        assertTrue(message.contains("模型未注册"));
        assertFalse(message.equals(ErrorCodes.MODEL_NOT_REGISTERED));
    }

    /**
     * 未登记的错误码原样返回，便于新增错误码时平滑退化。
     */
    @Test
    public void returnsUnknownCodeAsIs() {
        assertEquals("SOME_UNKNOWN_CODE", ErrorMessages.of("SOME_UNKNOWN_CODE"));
    }

    /**
     * 错误码为 null、空串或全空白时抛出 IllegalArgumentException。
     */
    @Test
    public void rejectsBlankCode() {
        assertThrows(IllegalArgumentException.class, () -> ErrorMessages.of(null));
        assertThrows(IllegalArgumentException.class, () -> ErrorMessages.of(""));
        assertThrows(IllegalArgumentException.class, () -> ErrorMessages.of("   "));
    }

    /**
     * 已登记的占位符按变量值替换；变量为 null 或参数为空时保留模板。
     */
    @Test
    public void interpolatesPlaceholders() {
        Map<String, String> args = new HashMap<>();
        args.put("provider", "VOLCENGINE");
        String message = ErrorMessages.of(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, args);
        assertTrue(message.contains("VOLCENGINE"));
        assertFalse(message.contains("{provider}"));

        assertEquals(ErrorMessages.of(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED),
                ErrorMessages.of(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, null));
        assertEquals(ErrorMessages.of(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED),
                ErrorMessages.of(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, Collections.emptyMap()));
    }

    /**
     * 未提供的占位符原样保留，便于发现漏填的变量。
     */
    @Test
    public void keepsMissingPlaceholder() {
        String message = ErrorMessages.of(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED,
                Collections.singletonMap("other", "x"));
        assertTrue(message.contains("{provider}"));
    }

    /**
     * 未闭合的占位符按普通文本处理，不抛异常。
     */
    @Test
    public void toleratesUnclosedPlaceholder() {
        String message = ErrorMessages.of(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED,
                Collections.singletonMap("provider", "{unclosed"));
        assertTrue(message.contains("{unclosed"));
    }

    /**
     * ErrorCodes 中的稳定错误码都应登记中文提示，避免新增后直接暴露裸错误码。
     */
    @Test
    public void allDeclaredCodesHaveMessages() throws IllegalAccessException {
        for (Field field : ErrorCodes.class.getFields()) {
            if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String code = (String) field.get(null);
            assertNotEquals(code, ErrorMessages.of(code), code);
        }
    }

    private static void assertNotEquals(String unexpected, String actual, String message) {
        assertFalse(message, unexpected.equals(actual));
    }
}