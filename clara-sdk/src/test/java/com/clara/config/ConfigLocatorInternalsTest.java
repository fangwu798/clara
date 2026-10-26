package com.clara.config;

import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConfigLocator} 私有工具方法的分支覆盖：标识规范化、引号剥离、模型配置筛选与摘要。
 */
public class ConfigLocatorInternalsTest {

    @Test
    public void normalizesProjectNameToAsciiIdentifier() throws Exception {
        Method method = method("toAsciiIdentifier", String.class);

        assertEquals("zhongwen_2_abc", method.invoke(null, "中文 2-abc"));
        assertEquals("p9haodian", method.invoke(null, "9号店"));
        assertEquals("a", method.invoke(null, "__a__"));
        assertEquals("", method.invoke(null, "!!!"));
    }

    @Test
    public void stripsMatchingQuotesOnly() throws Exception {
        Method method = method("unquote", String.class);

        assertEquals("abc", method.invoke(null, "\"abc\""));
        assertEquals("abc", method.invoke(null, "'abc'"));
        assertEquals("abc", method.invoke(null, "abc"));
        assertEquals("\"abc'", method.invoke(null, "\"abc'"));
    }

    @Test
    public void acceptsOnlyRealModelConfigFiles() throws Exception {
        Method method = method("isModelConfig", Path.class);

        assertTrue((Boolean) method.invoke(null, Paths.get("alpha.yml")));
        assertTrue((Boolean) method.invoke(null, Paths.get("beta.yaml")));
        assertFalse((Boolean) method.invoke(null, Paths.get("alpha.json")));
        assertFalse((Boolean) method.invoke(null, Paths.get("alpha.example.yml")));
        assertFalse((Boolean) method.invoke(null, Paths.get("config", "examples", "alpha.yml")));
    }

    @Test
    public void hashesWithSha256() throws Exception {
        Method method = method("sha256", String.class);

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                method.invoke(null, "abc"));
    }

    private static Method method(String name, Class<?>... parameterTypes) throws Exception {
        Method method = ConfigLocator.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }
}