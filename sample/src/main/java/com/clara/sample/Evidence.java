package com.clara.sample;

import java.io.PrintStream;

/**
 * 证据输出小工具：统一打印「标题 + 键值 + 耗时」，并以 ✅/⚠️/❌ 收尾。
 *
 * <p>仅用于联调验证程序，不属于任何 clara-* 业务模块。
 */
public final class Evidence {

    private final PrintStream out;
    private long startMillis = System.currentTimeMillis();

    public Evidence(PrintStream out) {
        this.out = out;
    }

    /** 打印场景标题，并重置计时起点。 */
    public void title(String title) {
        out.println();
        out.println("================================================================");
        out.println("  " + title);
        out.println("================================================================");
        startMillis = System.currentTimeMillis();
    }

    /** 打印一条键值证据。 */
    public void kv(String key, Object value) {
        out.println("  - " + key + " : " + value);
    }

    /** 打印一行说明文本。 */
    public void line(String text) {
        out.println("  " + text);
    }

    /** 距离本场景开始已经过去的毫秒数。 */
    public long elapsedMillis() {
        return System.currentTimeMillis() - startMillis;
    }

    /** 打印通过结论。 */
    public void pass(String capability, String detail) {
        out.println("✅ " + capability + " 通过（耗时 " + elapsedMillis() + " ms）"
                + (detail == null || detail.isEmpty() ? "" : "，" + detail));
    }

    /** 打印跳过结论与原因。 */
    public void skip(String capability, String reason) {
        out.println("⚠️ " + capability + " 跳过：" + reason + "（耗时 " + elapsedMillis() + " ms）");
    }

    /** 打印失败结论与原因。 */
    public void fail(String capability, String reason) {
        out.println("❌ " + capability + " 失败：" + reason + "（耗时 " + elapsedMillis() + " ms）");
    }
}
