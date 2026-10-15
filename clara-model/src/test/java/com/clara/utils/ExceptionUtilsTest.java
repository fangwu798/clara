package com.clara.utils;

import com.clara.enums.ModelProviderEnum;
import com.clara.exception.ClaraException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * 写入安全细节前对平台错误提示的清理规则：保留平台错误信息与 HTTP 状态码，去掉厂商侧请求标识。
 */
public class ExceptionUtilsTest {

    /**
     * 非 2xx 响应：请求标识被去掉，错误信息与状态码保留。
     */
    @Test
    public void dropsRequestIdFromHttpFailureDetail() {
        ClaraException failure = ExceptionUtils.providerFailure(ModelProviderEnum.VOLCENGINE, 403,
                "The request failed because your account has an overdue balance. "
                        + "Request id: 021792044653981b045cd2f7c61da91e37e7e70760c22da376e34");

        assertEquals("The request failed because your account has an overdue balance.",
                failure.getError().getSafeDetails().get("providerMessage"));
        assertEquals("模型平台调用失败：平台 VOLCENGINE，返回 403", failure.getError().getSafeMessage());
    }

    /**
     * 事件流内自报错误：同样去掉请求标识，平台错误码保留。
     */
    @Test
    public void dropsRequestIdFromEventFailureDetail() {
        ClaraException failure = ExceptionUtils.providerEventFailure(ModelProviderEnum.OPENAI, "rate_limit_exceeded",
                "Rate limit reached. Request ID: req_abc123");

        assertEquals("Rate limit reached.", failure.getError().getSafeDetails().get("providerMessage"));
        assertEquals("rate_limit_exceeded", failure.getError().getProviderCode());
    }

    /**
     * 没有请求标识时原文保留；只有请求标识或没有提示文本时不写入细节。
     */
    @Test
    public void keepsMessageWithoutRequestIdAndDropsIdentifierOnly() {
        assertEquals("Insufficient Balance",
                ExceptionUtils.providerFailure(ModelProviderEnum.DEEPSEEK, 402, "Insufficient Balance")
                        .getError().getSafeDetails().get("providerMessage"));
        assertNull(ExceptionUtils.providerFailure(ModelProviderEnum.DEEPSEEK, 402, " Request id: abc123 ")
                .getError().getSafeDetails().get("providerMessage"));
        assertFalse(ExceptionUtils.providerFailure(ModelProviderEnum.DEEPSEEK, 402, null)
                .getError().getSafeDetails().containsKey("providerMessage"));
    }
}
