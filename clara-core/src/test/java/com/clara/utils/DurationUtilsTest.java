package com.clara.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 耗时格式化规则：不足一分钟保持原单位，达到一分钟统一显示为分秒。
 */
public class DurationUtilsTest {

    /**
     * 秒级计时：59 秒内保持秒，60 秒起转换为分秒，且始终保留秒位。
     */
    @Test
    public void formatsSecondsWithMinuteBoundary() {
        assertEquals("0s", DurationUtils.formatSeconds(0L));
        assertEquals("59s", DurationUtils.formatSeconds(59L));
        assertEquals("1分0秒", DurationUtils.formatSeconds(60L));
        assertEquals("1分5秒", DurationUtils.formatSeconds(65L));
        assertEquals("61分1秒", DurationUtils.formatSeconds(3661L));
    }

    /**
     * 毫秒耗时：一分钟内保持毫秒，达到一分钟转换为分秒。
     */
    @Test
    public void formatsMillisWithMinuteBoundary() {
        assertEquals("0ms", DurationUtils.formatMillis(0L));
        assertEquals("59999ms", DurationUtils.formatMillis(59_999L));
        assertEquals("1分0秒", DurationUtils.formatMillis(60_000L));
        assertEquals("2分5秒", DurationUtils.formatMillis(125_000L));
    }

    /**
     * 负数按 0 处理，避免异常耗时在控制台输出负值。
     */
    @Test
    public void clampsNegativeValuesToZero() {
        assertEquals("0s", DurationUtils.formatSeconds(-1L));
        assertEquals("0ms", DurationUtils.formatMillis(-1L));
    }
}
