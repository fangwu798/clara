package com.clara.utils.deserialize;

import com.clara.core.annotation.ResponseAdapter;
import com.clara.core.model.IResponse;
import com.clara.core.transport.RawHttpResponse;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.FinishReasonEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.exception.ClaraException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ResponseDecoderSupport} 的单元测试：路径语法、类型转换、字段读写与注解匹配。
 *
 * <p>解码器把平台 JSON 映射到统一响应对象，路径或类型判断出错会直接导致字段丢失或误报协议错误，
 * 因此这里对每种支持的路径写法与类型都给出正反例。
 */
public class ResponseDecoderSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    public void valueAtSupportsDottedPathAndIndex() throws Exception {
        JsonNode root = json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}],\"usage\":{\"total_tokens\":9}}");

        assertEquals("hi", ResponseDecoderSupport.valueAt(root, "choices[0].message.content").asText());
        assertEquals(9, ResponseDecoderSupport.valueAt(root, "usage.total_tokens").asInt());
    }

    @Test
    public void valueAtSupportsTypeFilter() throws Exception {
        JsonNode root = json("{\"content\":[{\"type\":\"thinking\",\"text\":\"x\"},{\"type\":\"text\",\"text\":\"答案\"}]}");

        JsonNode found = ResponseDecoderSupport.valueAt(root, "content[type=text].text");

        assertEquals("答案", found.asText());
        assertNull(ResponseDecoderSupport.valueAt(root, "content[type=image].text"));
    }

    @Test
    public void valueAtReturnsNullForMalformedSegments() throws Exception {
        JsonNode root = json("{\"items\":[{\"v\":1}],\"text\":\"x\"}");

        assertNull(ResponseDecoderSupport.valueAt(root, "items[0"));
        assertNull(ResponseDecoderSupport.valueAt(root, "items[abc]"));
        assertNull(ResponseDecoderSupport.valueAt(root, "text[0]"));
        assertNull(ResponseDecoderSupport.valueAt(root, "missing.value"));
        assertNull(ResponseDecoderSupport.valueAt(root, "items[5].v"));
    }

    @Test
    public void presentRejectsNullEmptyAndNullNode() throws Exception {
        assertFalse(ResponseDecoderSupport.present(null));
        assertFalse(ResponseDecoderSupport.present(MAPPER.nullNode()));
        assertFalse(ResponseDecoderSupport.present(json("\"\"")));
        assertTrue(ResponseDecoderSupport.present(json("\"x\"")));
        assertTrue(ResponseDecoderSupport.present(json("0")));
    }

    @Test
    public void parseFailureBecomesProtocolError() {
        try {
            ResponseDecoderSupport.parse("{oops", ModelProviderEnum.ANTHROPIC);
            fail("expected protocol error");
        } catch (ClaraException expected) {
            assertTrue(expected.getMessage().contains("协议") || expected.getMessage().contains("protocol"));
        }
    }

    @Test
    public void writeSkipsNullAndSetsOtherwise() {
        SampleResponse target = new SampleResponse();

        ResponseDecoderSupport.write(field(BaseResponse.class, "text"), target, null);
        assertNull(target.text);

        ResponseDecoderSupport.write(field(BaseResponse.class, "text"), target, "value");
        assertEquals("value", target.text);
    }

    @Test
    public void appendTextInitializesThenConcatenates() {
        SampleResponse target = new SampleResponse();
        Field text = field(BaseResponse.class, "text");

        ResponseDecoderSupport.appendText(text, target, "a");
        ResponseDecoderSupport.appendText(text, target, "b");

        assertEquals("ab", target.text);
    }

    @Test
    public void convertsPrimitiveAndWrapperTypes() throws Exception {
        assertEquals("12", ResponseDecoderSupport.convert(String.class, json("12"), ModelProviderEnum.OPENAI));
        assertEquals(Integer.valueOf(12), ResponseDecoderSupport.convert(Integer.class, json("12"), ModelProviderEnum.OPENAI));
        assertEquals(Integer.valueOf(12), ResponseDecoderSupport.convert(Integer.TYPE, json("12"), ModelProviderEnum.OPENAI));
        assertEquals(Long.valueOf(12L), ResponseDecoderSupport.convert(Long.class, json("12"), ModelProviderEnum.OPENAI));
        assertEquals(Long.valueOf(12L), ResponseDecoderSupport.convert(Long.TYPE, json("12"), ModelProviderEnum.OPENAI));
        assertEquals(Double.valueOf(1.5d), ResponseDecoderSupport.convert(Double.class, json("1.5"), ModelProviderEnum.OPENAI));
        assertEquals(Double.valueOf(1.5d), ResponseDecoderSupport.convert(Double.TYPE, json("1.5"), ModelProviderEnum.OPENAI));
        assertEquals(Boolean.TRUE, ResponseDecoderSupport.convert(Boolean.class, json("true"), ModelProviderEnum.OPENAI));
        assertEquals(Boolean.TRUE, ResponseDecoderSupport.convert(Boolean.TYPE, json("true"), ModelProviderEnum.OPENAI));
    }

    @Test
    public void typeMismatchBecomesProtocolError() throws Exception {
        assertProtocolError(Integer.class, "\"text\"");
        assertProtocolError(Integer.class, "1.5");
        assertProtocolError(Long.class, "\"text\"");
        assertProtocolError(Double.class, "\"text\"");
        assertProtocolError(Boolean.class, "1");
    }

    @Test
    public void convertsFinishReasonAndOtherEnums() throws Exception {
        Object reason = ResponseDecoderSupport.convert(FinishReasonEnum.class, json("\"length\""), ModelProviderEnum.OPENAI);
        Object capability = ResponseDecoderSupport.convert(CapabilityKindEnum.class, json("\"chat\""), ModelProviderEnum.OPENAI);

        assertTrue(reason instanceof FinishReasonEnum);
        assertSame(CapabilityKindEnum.CHAT, capability);
    }

    @Test
    public void unknownEnumLiteralBecomesProtocolError() throws Exception {
        try {
            ResponseDecoderSupport.convert(CapabilityKindEnum.class, json("\"nope\""), ModelProviderEnum.OPENAI);
            fail("expected protocol error");
        } catch (ClaraException expected) {
            assertTrue(expected.getMessage().contains("协议") || expected.getMessage().contains("protocol"));
        }
    }

    @Test
    public void mappedValueUsesJacksonForComplexTypes() throws Exception {
        Object list = ResponseDecoderSupport.convert(List.class, json("[1,2]"), ModelProviderEnum.OPENAI);
        assertTrue(list instanceof List);
        assertEquals(2, ((List<?>) list).size());

        try {
            ResponseDecoderSupport.convert(java.util.Map.class, json("\"text\""), ModelProviderEnum.OPENAI);
            fail("expected protocol error");
        } catch (ClaraException expected) {
            assertTrue(expected.getMessage().contains("协议") || expected.getMessage().contains("protocol"));
        }
    }

    @Test
    public void newInstanceRequiresNoArgConstructor() {
        SampleResponse instance = ResponseDecoderSupport.newInstance(SampleResponse.class);
        assertEquals(SampleResponse.class, instance.getCls());

        try {
            ResponseDecoderSupport.newInstance(NoDefaultConstructor.class);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no-arg"));
        }
    }

    @Test
    public void fieldsListsParentFieldsFirstAndSkipsStatics() {
        List<Field> fields = ResponseDecoderSupport.fields(ChildResponse.class);

        assertEquals(Arrays.asList("base", "text", "child"), Arrays.asList(
                fields.get(0).getName(), fields.get(1).getName(), fields.get(2).getName()));
    }

    @Test
    public void matchPrefersDeclaredProviderAndCapability() {
        Field plain = field(SampleResponse.class, "plain");
        Field scoped = field(SampleResponse.class, "scoped");
        Field generic = field(SampleResponse.class, "generic");

        assertTrue(ResponseDecoderSupport.match(plain, ModelProviderEnum.OPENAI, CapabilityKindEnum.CHAT) != null);
        assertNull(ResponseDecoderSupport.match(plain, ModelProviderEnum.KIMI, CapabilityKindEnum.CHAT));
        assertNull(ResponseDecoderSupport.match(plain, ModelProviderEnum.OPENAI, CapabilityKindEnum.IMAGE));
        assertTrue(ResponseDecoderSupport.match(scoped, ModelProviderEnum.ANTHROPIC, CapabilityKindEnum.CHAT) != null);
        assertTrue(ResponseDecoderSupport.match(generic, ModelProviderEnum.KIMI, CapabilityKindEnum.CHAT) != null);
        assertNull(ResponseDecoderSupport.match(generic, ModelProviderEnum.KIMI, CapabilityKindEnum.IMAGE));
    }

    private static void assertProtocolError(Class<?> type, String node) throws Exception {
        try {
            ResponseDecoderSupport.convert(type, json(node), ModelProviderEnum.OPENAI);
            fail("expected protocol error for " + type + " <- " + node);
        } catch (ClaraException expected) {
            assertTrue(expected.getMessage().contains("协议") || expected.getMessage().contains("protocol"));
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static class BaseResponse implements IResponse {

        private String base;

        String text;

        @Override
        @SuppressWarnings("unchecked")
        public <T extends IResponse> Class<T> getCls() {
            return (Class<T>) getClass();
        }

        @Override
        public RawHttpResponse toResponse() {
            return null;
        }
    }

    private static final class SampleResponse extends BaseResponse {

        private static final String CONSTANT = "ignored";

        @ResponseAdapter(type = ModelProviderEnum.OPENAI, path = "choices[0].message.content")
        private String plain;

        @ResponseAdapter(type = ModelProviderEnum.ANTHROPIC, path = "content[type=text].text")
        private String scoped;

        @ResponseAdapter(path = "output")
        private String generic;
    }

    private static final class ChildResponse extends BaseResponse {

        private String child;
    }

    private static final class NoDefaultConstructor extends BaseResponse {

        @SuppressWarnings("unused")
        private final String value;

        private NoDefaultConstructor(String value) {
            this.value = value;
        }
    }
}