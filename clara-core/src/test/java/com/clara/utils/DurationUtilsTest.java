package com.clara.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 耗时格式化规则：不足一分钟保持原单位，达到一分钟显示分秒，达到一小时进位为小时，达到一天进位为天。
 */
public class DurationUtilsTest {

    /**
     * 秒级计时：59 秒内保持秒，60 秒起显示分秒；分不会超过 59，满 60 分由小时承接。
     */
    @Test
    public void formatsSecondsWithMinuteBoundary() {
        assertEquals("0s", DurationUtils.formatSeconds(0L));
        assertEquals("59s", DurationUtils.formatSeconds(59L));
        assertEquals("1分0秒", DurationUtils.formatSeconds(60L));
        assertEquals("1分5秒", DurationUtils.formatSeconds(65L));
        assertEquals("59分59秒", DurationUtils.formatSeconds(3_599L));
    }

    /**
     * 满一小时进位为小时，且保留分、秒位。
     */
    @Test
    public void formatsSecondsWithHourBoundary() {
        assertEquals("1小时0分0秒", DurationUtils.formatSeconds(3_600L));
        assertEquals("1小时1分1秒", DurationUtils.formatSeconds(3_661L));
        assertEquals("23小时59分59秒", DurationUtils.formatSeconds(86_399L));
    }

    /**
     * 满一天进位为天，且保留小时、分、秒位。
     */
    @Test
    public void formatsSecondsWithDayBoundary() {
        assertEquals("1天0小时0分0秒", DurationUtils.formatSeconds(86_400L));
        assertEquals("1天1小时1分1秒", DurationUtils.formatSeconds(90_061L));
        assertEquals("2天3小时4分5秒", DurationUtils.formatSeconds(183_845L));
    }

    /**
     * 毫秒耗时：一分钟内保持毫秒，达到一分钟转换为分秒，进位规则与秒级一致。
     */
    @Test
    public void formatsMillisWithMinuteBoundary() {
        assertEquals("0ms", DurationUtils.formatMillis(0L));
        assertEquals("59999ms", DurationUtils.formatMillis(59_999L));
        assertEquals("1分0秒", DurationUtils.formatMillis(60_000L));
        assertEquals("2分5秒", DurationUtils.formatMillis(125_000L));
        assertEquals("1小时0分0秒", DurationUtils.formatMillis(3_600_000L));
        assertEquals("1天0小时0分0秒", DurationUtils.formatMillis(86_400_000L));
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
