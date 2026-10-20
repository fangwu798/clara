package com.clara.usage;

import com.clara.kernel.message.delta.TokenUsage;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link CapabilityUsageLedger} 的分项统计测试：视频 token、图片张数与图谱查询互不混淆。
 */
public class CapabilityUsageLedgerTest {

    @Test
    public void reportsVideoImageAndGraphSeparately() {
        CapabilityUsageLedger ledger = new CapabilityUsageLedger();
        Map<String, Object> videoUsage = new LinkedHashMap<>();
        videoUsage.put("completion_tokens", 108900);
        videoUsage.put("total_tokens", 108900);

        ledger.recordVideo(videoUsage);
        ledger.recordImage(new TokenUsage(0, 0, null, null), 2);
        ledger.recordGraph(1234L);

        String report = ledger.report();
        assertTrue(report.contains("视频：1 个任务；completion=108900，total=108900"));
        assertTrue(report.contains("图片：2 张；平台未返回 token"));
        assertTrue(report.contains("图谱：1 次查询，返回 1234 字符"));
    }

    @Test
    public void resetsAllBuckets() {
        CapabilityUsageLedger ledger = new CapabilityUsageLedger();
        Map<String, Object> videoUsage = new LinkedHashMap<>();
        videoUsage.put("total_tokens", 10);
        ledger.recordVideo(videoUsage);
        ledger.recordImage(new TokenUsage(1, 2, 3, null), 1);
        ledger.recordGraph(5L);

        ledger.reset();

        String report = ledger.report();
        assertTrue(report.contains("视频：0 个任务"));
        assertTrue(report.contains("图片：0 张"));
        assertTrue(report.contains("图谱：0 次查询"));
        assertFalse(report.contains("108900"));
    }
}
