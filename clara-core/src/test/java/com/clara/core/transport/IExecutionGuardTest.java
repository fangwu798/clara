package com.clara.core.transport;

import com.clara.enums.HttpTransportErrorKindEnum;
import com.clara.exception.HttpTransportException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link IExecutionGuard} 的契约测试：空守卫与取消 / 超时的快速失败。
 *
 * <p>守卫是发送前与读取过程中的统一检查点；误判会导致请求在已取消后继续发往平台，
 * 或把可重试的超时归成别的错误类型。
 */
public class IExecutionGuardTest {

    @Test
    public void noneGuardNeverBlocks() {
        IExecutionGuard guard = IExecutionGuard.NONE;

        assertFalse(guard.isCancelled());
        assertEquals(Long.MAX_VALUE, guard.remainingMillis());
        guard.check();
    }

    @Test
    public void cancelledGuardFailsFast() {
        IExecutionGuard guard = new IExecutionGuard() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public long remainingMillis() {
                return 1000L;
            }
        };

        HttpTransportException exception = assertThrows(HttpTransportException.class, guard::check);
        assertEquals(HttpTransportErrorKindEnum.CANCELLED, exception.getKind());
        assertFalse(exception.isRetryable());
    }

    @Test
    public void expiredDeadlineFailsAsTimeout() {
        HttpTransportException zero = assertThrows(HttpTransportException.class,
                guard(0L)::check);
        HttpTransportException negative = assertThrows(HttpTransportException.class,
                guard(-1L)::check);

        assertEquals(HttpTransportErrorKindEnum.TIMEOUT, zero.getKind());
        assertEquals(HttpTransportErrorKindEnum.TIMEOUT, negative.getKind());
        assertTrue(zero.isRetryable());
    }

    private static IExecutionGuard guard(long remaining) {
        return new IExecutionGuard() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public long remainingMillis() {
                return remaining;
            }
        };
    }
}