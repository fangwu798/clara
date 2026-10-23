package com.clara.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 控制台排版用的显示宽度与补齐规则：东亚全角字符占两列，其余占一列。
 */
public class StringUtilsTest {

    /**
     * 全角字符按两列计，ASCII 按一列计。
     */
    @Test
    public void countsFullWidthCharactersAsTwoColumns() {
        assertEquals(0, StringUtils.displayWidth(""));
        assertEquals(6, StringUtils.displayWidth("/model"));
        assertEquals(6, StringUtils.displayWidth("[可用]"));
        assertEquals(8, StringUtils.displayWidth("[不可用]"));
        assertEquals(26, StringUtils.displayWidth("读取本地目录、整理技术文档"));
    }

    /**
     * 补齐后达到目标显示宽度；已足够宽时原样返回。
     */
    @Test
    public void padsToTargetDisplayWidth() {
        assertEquals("/model      ", StringUtils.padRight("/model", 12));
        assertEquals("读取      ", StringUtils.padRight("读取", 10));
        assertEquals("生成图片", StringUtils.padRight("生成图片", 8));
        assertEquals("abcdefghijk", StringUtils.padRight("abcdefghijk", 3));
    }

    /**
     * 空白判断覆盖 null、空串、全空白与正常文本。
     */
    @Test
    public void detectsBlankAndNotBlank() {
        assertTrue(StringUtils.isBlank(null));
        assertTrue(StringUtils.isBlank(""));
        assertTrue(StringUtils.isBlank(" \t\r\n"));
        assertFalse(StringUtils.isBlank(" value "));
        assertTrue(StringUtils.isNotBlank(" value "));
        assertFalse(StringUtils.isNotBlank(" "));
    }

    /**
     * 非空校验：空值时带字段名报错，正常值原样返回。
     */
    @Test
    public void requiresNotBlankValue() {
        assertEquals("value", StringUtils.requireNotBlank("value", "name"));
        try {
            StringUtils.requireNotBlank("  ", "model");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("model"));
        }
        try {
            StringUtils.requireNotBlank(null, "provider");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("provider"));
        }
    }

    /**
     * 空白归一与「空内容」归一的口径不同：后者保留全空白串。
     */
    @Test
    public void normalizesBlankAndEmptyContent() {
        assertEquals("value", StringUtils.blankToNull("value"));
        assertEquals(null, StringUtils.blankToNull(null));
        assertEquals(null, StringUtils.blankToNull("   "));

        assertEquals("value", StringUtils.emptyToNull("value"));
        assertEquals("   ", StringUtils.emptyToNull("   "));
        assertEquals(null, StringUtils.emptyToNull(null));
        assertEquals(null, StringUtils.emptyToNull(""));
    }

    /**
     * UTF-8 字节数按编码后长度计算，严格解码拒绝非法字节。
     */
    @Test
    public void measuresAndDecodesUtf8() throws Exception {
        assertEquals(3, StringUtils.utf8Length("abc"));
        assertEquals(6, StringUtils.utf8Length("中文"));
        assertEquals("中文", StringUtils.decodeUtf8Strict("中文".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        try {
            StringUtils.decodeUtf8Strict(new byte[] {(byte) 0xC3, (byte) 0x28});
            throw new AssertionError("expected CharacterCodingException");
        } catch (java.nio.charset.CharacterCodingException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }
}