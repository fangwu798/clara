package com.clara.usage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link TokenCountFormatter} 的区间与小数位测试。
 */
public class TokenCountFormatterTest {

    /**
     * 一万以下不带单位，一万及以上按区间换算并保留两位小数。
     */
    @Test
    public void formatsByMagnitude() {
        assertEquals("0", TokenCountFormatter.format(0L));
        assertEquals("9999", TokenCountFormatter.format(9_999L));
        assertEquals("1.00万", TokenCountFormatter.format(10_000L));
        assertEquals("1.23万", TokenCountFormatter.format(12_345L));
        assertEquals("1000.00万", TokenCountFormatter.format(9_999_999L));
        assertEquals("1000.00万", TokenCountFormatter.format(10_000_000L));
        assertEquals("1.00千万", TokenCountFormatter.format(10_000_001L));
        assertEquals("1.09千万", TokenCountFormatter.format(10_890_000L));
        assertEquals("10.00千万", TokenCountFormatter.format(99_999_999L));
        assertEquals("1.00亿", TokenCountFormatter.format(100_000_000L));
        assertEquals("12.35亿", TokenCountFormatter.format(1_234_567_890L));
    }

    /**
     * 换算结果按四舍五入保留两位小数。
     */
    @Test
    public void roundsToTwoDecimals() {
        assertEquals("1.24万", TokenCountFormatter.format(12_350L));
        assertEquals("1.23万", TokenCountFormatter.format(12_349L));
    }
}
