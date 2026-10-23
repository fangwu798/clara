package com.clara.dto.record;

import com.clara.core.model.IRequest;
import com.clara.core.model.IResponse;
import com.clara.core.transport.RawHttpRequest;
import com.clara.core.transport.RawHttpResponse;
import com.clara.enums.ModelProviderEnum;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link CallRecord} 的契约测试：四要素必填校验与值语义。
 *
 * <p>调用记录是后续总结与审计的原始依据，缺字段的记录没有意义，因此在构造期就拒绝。
 */
public class CallRecordTest {

    private static final IRequest REQUEST = new IRequest() {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends IRequest> Class<T> getCls() {
            return (Class<T>) IRequest.class;
        }

        @Override
        public RawHttpRequest toRequest() {
            return null;
        }

        @Override
        public Boolean getStream() {
            return Boolean.FALSE;
        }
    };

    private static final IResponse RESPONSE = new IResponse() {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends IResponse> Class<T> getCls() {
            return (Class<T>) IResponse.class;
        }

        @Override
        public RawHttpResponse toResponse() {
            return null;
        }
    };

    @Test
    public void exposesAllRecordFields() {
        CallRecord record = new CallRecord(REQUEST, RESPONSE, ModelProviderEnum.DEEPSEEK, "deepseek-v4-pro");

        assertEquals(REQUEST, record.getRequest());
        assertEquals(RESPONSE, record.getResponse());
        assertEquals(ModelProviderEnum.DEEPSEEK, record.getProvider());
        assertEquals("deepseek-v4-pro", record.getModelType());
    }

    @Test
    public void modelTypeMayBeNull() {
        CallRecord record = new CallRecord(REQUEST, RESPONSE, ModelProviderEnum.OPENAI, null);

        assertNull(record.getModelType());
    }

    @Test
    public void rejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new CallRecord(null, RESPONSE, ModelProviderEnum.OPENAI, "m"));
        assertThrows(IllegalArgumentException.class,
                () -> new CallRecord(REQUEST, null, ModelProviderEnum.OPENAI, "m"));
        assertThrows(IllegalArgumentException.class,
                () -> new CallRecord(REQUEST, RESPONSE, null, "m"));
    }

    @Test
    public void comparesByAllFields() {
        CallRecord one = new CallRecord(REQUEST, RESPONSE, ModelProviderEnum.KIMI, "kimi-k2.6");
        CallRecord same = new CallRecord(REQUEST, RESPONSE, ModelProviderEnum.KIMI, "kimi-k2.6");
        CallRecord other = new CallRecord(REQUEST, RESPONSE, ModelProviderEnum.KIMI, "kimi-k2.7");

        assertEquals(one, same);
        assertEquals(one.hashCode(), same.hashCode());
        assertFalse(one.equals(other));
        assertFalse(one.equals("other"));
        assertTrue(one.equals(one));
        assertTrue(one.toString().contains("kimi-k2.6"));
    }
}