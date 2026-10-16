package com.clara.config;

import com.clara.enums.ModelProviderEnum;
import com.clara.exception.ClaraError;
import com.clara.exception.ErrorCategory;
import com.clara.utils.ExceptionUtils;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ModelFailover} 的单元测试：判定「哪些失败值得换模型」与「按什么顺序换」。
 *
 * <p>夹具用假路径拼出模型配置清单（本类只看文件名推导别名，不读文件、不访问网络），
 * 错误则用 {@link ExceptionUtils} 的真实工厂构造，保证口径与线上失败一致。
 */
public class ModelFailoverTest {

    /**
     * 模型配置目录：别名 = 文件名去掉扩展名。
     */
    private static final Path DIRECTORY = Paths.get("config", "model-providers");

    private static final Path DEEPSEEK = DIRECTORY.resolve("deepseek-response.yml");
    private static final Path KIMI = DIRECTORY.resolve("kimi-response.yml");
    private static final Path OPENAI = DIRECTORY.resolve("openai.yml");

    @Test
    public void transportAndTimeoutFailuresAreUnavailable() {
        assertTrue(ModelFailover.isUnavailable(ExceptionUtils.transportIoError().getError()));
        assertTrue(ModelFailover.isUnavailable(ExceptionUtils.requestTimeout().getError()));
    }

    @Test
    public void platformStatusFailuresAreUnavailable() {
        int[] statuses = {401, 403, 429, 500, 502, 503};
        for (int status : statuses) {
            assertTrue("HTTP " + status + " 应视为模型不可用",
                    ModelFailover.isUnavailable(ExceptionUtils.providerFailure(ModelProviderEnum.DEEPSEEK, status).getError()));
        }
    }

    @Test
    public void missingCredentialIsUnavailable() {
        assertTrue(ModelFailover.isUnavailable(
                ExceptionUtils.credentialUnavailable(ModelProviderEnum.KIMI).getError()));
    }

    @Test
    public void emptyFailureWithoutErrorDetailsIsUnavailable() {
        // 编排在「模型一个字节都没回」时给出 null 错误，这种情况同样该换模型
        assertTrue(ModelFailover.isUnavailable(null));
    }

    @Test
    public void cancellationIsNotUnavailable() {
        assertFalse(ModelFailover.isUnavailable(ClaraError.cancelled("trace")));
    }

    @Test
    public void requestSideAndPolicyFailuresAreNotUnavailable() {
        ErrorCategory[] categories = {ErrorCategory.VALIDATION, ErrorCategory.RESOURCE_LIMIT,
                ErrorCategory.CONTENT_SAFETY, ErrorCategory.CAPABILITY, ErrorCategory.INTERNAL,
                ErrorCategory.UNKNOWN_SIDE_EFFECT, ErrorCategory.CONFLICT};
        for (ErrorCategory category : categories) {
            assertFalse(category + " 不该触发换模型", ModelFailover.isUnavailable(error(category)));
        }
    }

    @Test
    public void attemptOrderStartsFromCurrentThenFollowsRankedOrder() {
        List<Path> order = ModelFailover.attemptOrder(Arrays.asList(DEEPSEEK, KIMI, OPENAI), KIMI);
        assertEquals(Arrays.asList(KIMI, DEEPSEEK, OPENAI), order);
    }

    @Test
    public void attemptOrderDeduplicatesSameModelByAlias() {
        // 同一份配置以绝对路径传入时，不能因为路径字符串不同就被排两次（会白打一次失败请求）
        List<Path> order = ModelFailover.attemptOrder(
                Arrays.asList(DEEPSEEK.toAbsolutePath(), KIMI), DEEPSEEK);
        assertEquals(Arrays.asList(DEEPSEEK, KIMI), order);
    }

    @Test
    public void attemptOrderFollowsRankedOrderWhenCurrentUnknown() {
        List<Path> order = ModelFailover.attemptOrder(Arrays.asList(KIMI, OPENAI), null);
        assertEquals(Arrays.asList(KIMI, OPENAI), order);
    }

    @Test
    public void attemptOrderFallsBackToCurrentWhenNoCandidates() {
        assertEquals(Collections.singletonList(DEEPSEEK), ModelFailover.attemptOrder(null, DEEPSEEK));
        assertEquals(Collections.singletonList(DEEPSEEK), ModelFailover.attemptOrder(Collections.<Path>emptyList(), DEEPSEEK));
    }

    @Test
    public void attemptOrderIgnoresNullEntriesAndKeepsCurrentFirst() {
        List<Path> order = ModelFailover.attemptOrder(Arrays.asList(null, KIMI), DEEPSEEK);
        assertEquals(Arrays.asList(DEEPSEEK, KIMI), order);
    }

    @Test
    public void attemptOrderIsEmptyWhenNothingUsable() {
        assertTrue(ModelFailover.attemptOrder(null, null).isEmpty());
    }

    /**
     * 造一个指定类别的错误：错误码用类别名，只为让断言的失败信息可读。
     */
    private static ClaraError error(ErrorCategory category) {
        return ExceptionUtils.fail(category.name(), category, false).getError();
    }
}
