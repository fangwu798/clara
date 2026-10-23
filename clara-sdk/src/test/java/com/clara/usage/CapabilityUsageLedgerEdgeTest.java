package com.clara.usage;

import com.clara.kernel.message.delta.TokenUsage;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Missing / negative usage accounting: unknown values must not be reported as zero tokens. */
public class CapabilityUsageLedgerEdgeTest {

    @Test
    public void imageSummaryHandlesMissingAndNegativeCounts() {
        assertEquals("0 \u5f20\uff0c\u5e73\u53f0\u672a\u8fd4\u56de token",
                CapabilityUsageLedger.describeImage(null, null));
        assertEquals("0 \u5f20\uff0c\u5e73\u53f0\u672a\u8fd4\u56de token",
                CapabilityUsageLedger.describeImage(new TokenUsage(0, 0, null, null), -3));
        assertTrue(CapabilityUsageLedger.describeImage(new TokenUsage(120, 30, null, null), 1)
                .contains("total=150"));
        assertTrue(CapabilityUsageLedger.describeImage(new TokenUsage(120, 30, 300, null), 1)
                .contains("total=300"));
    }

    @Test
    public void videoSummaryHandlesMissingValuesAndFallbacks() {
        String missing = "\u5e73\u53f0\u672a\u8fd4\u56de token";
        assertEquals(missing, CapabilityUsageLedger.describeVideo(null));
        assertEquals(missing, CapabilityUsageLedger.describeVideo(new LinkedHashMap<String, Object>()));
        assertEquals(missing, CapabilityUsageLedger.describeVideo(values("input_tokens", "x")));
        assertTrue(CapabilityUsageLedger.describeVideo(values("completion_tokens", 9L, "output_tokens", 99L))
                .contains("completion=9"));
        assertTrue(CapabilityUsageLedger.describeVideo(values("total_tokens", 12L))
                .contains("total=12"));
    }

    @Test
    public void ledgerIgnoresNonNumericAndNegativeUsage() {
        CapabilityUsageLedger ledger = new CapabilityUsageLedger();
        ledger.recordVideo(values("prompt_tokens", "not-a-number",
                "completion_tokens", -5L, "total_tokens", -1L));
        ledger.recordImage(null, null);
        ledger.recordGraph(-10L);

        String report = ledger.report();
        assertTrue(report.contains("\u89c6\u9891\uff1a1 \u4e2a\u4efb\u52a1\uff1b"
                + "\u5e73\u53f0\u672a\u8fd4\u56de token"));
        assertTrue(report.contains("\u56fe\u7247\uff1a0 \u5f20\uff1b"
                + "\u5e73\u53f0\u672a\u8fd4\u56de token"));
        assertTrue(report.contains("\u56fe\u8c31\uff1a1 \u6b21\u67e5\u8be2\uff0c\u8fd4\u56de 0 \u5b57\u7b26"));
    }

    private static Map<String, Object> values(Object... keys) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keys.length; index += 2) {
            values.put(String.valueOf(keys[index]), keys[index + 1]);
        }
        return values;
    }
}
