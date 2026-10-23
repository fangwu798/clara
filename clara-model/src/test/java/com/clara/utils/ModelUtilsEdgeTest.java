package com.clara.utils;

import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallContent;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ClaraException;
import com.clara.model.token.TiktokenTokenCountEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Utility coverage for protocol JSON access, token estimation and error factories.
 */
public class ModelUtilsEdgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void jsonTextAccessHonoursStrictAndLooseModes() throws Exception {
        assertEquals("x", JsonNodeUtils.textOrNull(MAPPER.readTree("\"x\"")));
        assertNull(JsonNodeUtils.textOrNull(null));
        assertNull(JsonNodeUtils.textOrNull(NullNode.getInstance()));
        assertNull(JsonNodeUtils.textOrNull(MAPPER.readTree("{\"a\":1}")));

        assertNull(JsonNodeUtils.strictTextOrNull((JsonNode) null));
        assertNull(JsonNodeUtils.strictTextOrNull(NullNode.getInstance()));
        assertEquals("", JsonNodeUtils.strictTextOrNull(MAPPER.readTree("\"\"")));
        try {
            JsonNodeUtils.strictTextOrNull(MAPPER.readTree("1"));
            fail("non textual node must fail");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }

        JsonNode parent = MAPPER.readTree("{\"text\":\"v\",\"num\":3,\"bad\":\"s\",\"long\":9223372036854775807,\"d\":1.5}");
        assertEquals("v", JsonNodeUtils.strictTextOrNull(parent, "text"));
        assertNull(JsonNodeUtils.strictTextOrNull(parent, "missing"));
        assertEquals(3, JsonNodeUtils.getIntValue(parent, "num"));
        assertEquals(0, JsonNodeUtils.getIntValue(parent, "missing"));
        assertEquals(0, JsonNodeUtils.getIntValue(parent, "bad"));
        assertEquals(0, JsonNodeUtils.getIntValue(parent, "long"));
        assertEquals(Integer.valueOf(3), JsonNodeUtils.optionalIntValue(parent, "num"));
        assertNull(JsonNodeUtils.optionalIntValue(parent, "missing"));
        assertNull(JsonNodeUtils.optionalIntValue(parent, "bad"));
        assertNull(JsonNodeUtils.optionalIntValue(parent, "long"));
        assertNull(JsonNodeUtils.optionalIntValue(parent, "d"));
    }

    @Test
    public void tiktokenEstimatesEveryRoleAndRejectsInvalidInput() {
        try {
            new TiktokenTokenCountEstimator("  ");
            fail("blank encoding must fail");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new TiktokenTokenCountEstimator((String) null);
            fail("null encoding must fail");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            new TiktokenTokenCountEstimator((EncodingType) null);
            fail("null encoding type must fail");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }

        TiktokenTokenCountEstimator estimator = new TiktokenTokenCountEstimator(EncodingType.CL100K_BASE);
        try {
            estimator.estimate(null);
            fail("null messages must fail");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }

        List<com.clara.core.message.IContent> messages = new ArrayList<>();
        messages.add(null);
        messages.add(new com.clara.kernel.message.delta.SystemContent("system prompt"));
        messages.add(new com.clara.kernel.message.delta.UserContent("user text"));
        messages.add(new com.clara.kernel.message.delta.AssistantContent(null));
        messages.add(new com.clara.kernel.message.delta.ToolContent("call-1", "tool output"));

        ToolCall call = new ToolCall();
        call.setId("call-1");
        ToolCallFunction function = new ToolCallFunction();
        function.setName("lookup");
        function.setArguments("{\"q\":\"x\"}");
        call.setFunction(function);
        ToolCall noFunction = new ToolCall();
        noFunction.setId("call-2");
        messages.add(new ToolCallContent("reasoning", Arrays.asList(call, noFunction)));

        assertTrue(estimator.estimate(messages) > 0);
        assertEquals(0, estimator.estimate(Collections.<com.clara.core.message.IContent>singletonList(null)));
    }

    @Test
    public void errorFactoriesMapToCategoriesAndMessages() {
        assertCategory(ErrorCategoryEnum.AUTHORIZATION, ExceptionUtils.endpointDenied());
        assertCategory(ErrorCategoryEnum.RESOURCE_LIMIT, ExceptionUtils.requestTooLarge());
        assertCategory(ErrorCategoryEnum.RESOURCE_LIMIT, ExceptionUtils.requestTooLarge(null));
        assertCategory(ErrorCategoryEnum.RESOURCE_LIMIT, ExceptionUtils.requestTooLarge(com.clara.enums.ModelProviderEnum.DEEPSEEK));
        assertCategory(ErrorCategoryEnum.CONFIGURATION, ExceptionUtils.mediaTransportUnsupported(null));
        assertCategory(ErrorCategoryEnum.RESOURCE_LIMIT, ExceptionUtils.responseTooLarge());
        ClaraException timeout = ExceptionUtils.requestTimeout();
        assertCategory(ErrorCategoryEnum.TIMEOUT, timeout);
        assertTrue(timeout.getError().isRetryable());
        assertCategory(ErrorCategoryEnum.EXTERNAL_SERVICE, ExceptionUtils.protocolError());
        assertCategory(ErrorCategoryEnum.EXTERNAL_SERVICE, ExceptionUtils.protocolError(null));
        assertCategory(ErrorCategoryEnum.CAPABILITY, ExceptionUtils.capabilityUnsupported());
        assertCategory(ErrorCategoryEnum.EXTERNAL_SERVICE, ExceptionUtils.transportIoError());
        assertCategory(ErrorCategoryEnum.CONFIGURATION, ExceptionUtils.credentialUnavailable(null));

        ClaraException provider = ExceptionUtils.providerFailure(null, 500);
        assertCategory(ErrorCategoryEnum.EXTERNAL_SERVICE, provider);
        assertTrue(provider.getMessage().contains("500"));
        assertCategory(ErrorCategoryEnum.AUTHENTICATION, ExceptionUtils.providerFailure(null, 401));
        ClaraException provider2 = ExceptionUtils.providerFailure(com.clara.enums.ModelProviderEnum.KIMI, 429,
                "slow down Request id: 021792044653981b-abcdef");
        assertCategory(ErrorCategoryEnum.RATE_LIMIT, provider2);
        assertEquals("slow down", provider2.getError().getSafeDetails().get("providerMessage"));

        ClaraException event = ExceptionUtils.providerEventFailure(null, null);
        assertCategory(ErrorCategoryEnum.EXTERNAL_SERVICE, event);
        ClaraException event2 = ExceptionUtils.providerEventFailure(com.clara.enums.ModelProviderEnum.DEEPSEEK, "x",
                "Request id: abc");
        assertNull(event2.getError().getSafeDetails().get("providerMessage"));

        ExceptionUtils.providerEventFailure(com.clara.enums.ModelProviderEnum.DEEPSEEK, "x", "plain message");
        ExceptionUtils.providerEventFailure(com.clara.enums.ModelProviderEnum.DEEPSEEK, "x", null);
        ClaraException generic = ExceptionUtils.fail("CUSTOM", ErrorCategoryEnum.VALIDATION, true);
        assertEquals("CUSTOM", generic.getError().getCode());
        ClaraException withProvider = ExceptionUtils.fail("CUSTOM2", ErrorCategoryEnum.VALIDATION, false,
                com.clara.enums.ModelProviderEnum.OPENAI);
        assertEquals("OPENAI", withProvider.getError().getProvider());
        ClaraException withCode = ExceptionUtils.fail("CUSTOM3", ErrorCategoryEnum.VALIDATION, false,
                com.clara.enums.ModelProviderEnum.OPENAI, "E-1");
        assertEquals("E-1", withCode.getError().getProviderCode());
    }

    private static void assertCategory(ErrorCategoryEnum expected, ClaraException exception) {
        assertEquals(expected, exception.getError().getCategory());
        assertNotNull(exception.getMessage());
    }
}