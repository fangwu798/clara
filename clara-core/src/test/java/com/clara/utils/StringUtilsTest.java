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
}
