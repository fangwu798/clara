package com.clara.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConfigLocator#cliSessionId()} 的单元测试：会话标识由工作目录稳定推导，
 * 这是「退出后再进入同一目录能续接历史」的前提（若每次随机，历史将永远对不上）。
 */
public class ConfigLocatorTest {

    @Test
    public void sessionIdIsStableForSameWorkingDirectory() {
        String first = ConfigLocator.cliSessionId();
        String second = ConfigLocator.cliSessionId();

        assertEquals("同一工作目录必须得到同一会话标识", first, second);
        assertTrue("应以 cli- 前缀标识控制台会话，实际：" + first, first.startsWith("cli-"));
        assertEquals("cli-".length() + 16, first.length());
    }
}
