package com.clara.utils;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link NumberUtils#parseDigitsOrNull(String)} 的边界测试。
 *
 * <p>为什么测：SSE 的 retry 等字段来自外部服务，非法值必须安静地返回 null 交给调用方忽略，
 * 不能在解析层抛异常中断整条流。
 */
public class NumberUtilsTest {

    @Test
    public void nullAndEmptyReturnNull() {
        assertNull(NumberUtils.parseDigitsOrNull(null));
        assertNull(NumberUtils.parseDigitsOrNull(""));
    }

    @Test
    public void nonDigitsReturnNull() {
        assertNull(NumberUtils.parseDigitsOrNull("12a"));
        assertNull(NumberUtils.parseDigitsOrNull(" 12"));
        assertNull(NumberUtils.parseDigitsOrNull("-1"));
        assertNull(NumberUtils.parseDigitsOrNull("1.5"));
    }

    @Test
    public void digitsAreParsed() {
        assertEquals(Long.valueOf(0L), NumberUtils.parseDigitsOrNull("0"));
        assertEquals(Long.valueOf(1500L), NumberUtils.parseDigitsOrNull("1500"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), NumberUtils.parseDigitsOrNull(String.valueOf(Long.MAX_VALUE)));
    }

    @Test
    public void overflowReturnsNullInsteadOfThrowing() {
        assertNull(NumberUtils.parseDigitsOrNull("9223372036854775808"));
    }
}