package com.clara;

import com.clara.core.annotation.PublicApi;
import com.clara.core.model.IStreamModel;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 公共 API 契约守卫：宿主可直接依赖的入口必须带 {@link PublicApi} 标记。
 *
 * <p>新增对外入口时同步补标记与该用例，避免内部实现被误当作稳定契约发布。
 */
public class PublicApiContractTest {

    @Test
    public void embeddingEntryPointsAreMarkedPublic() {
        assertMarked(ApplicationBootstrap.class);
        assertMarked(ConversationRequest.class);
        assertMarked(ConversationResult.class);
        assertMarked(IStreamModel.class);
    }

    private static void assertMarked(Class<?> type) {
        PublicApi annotation = type.getAnnotation(PublicApi.class);
        assertNotNull(type.getName() + " 缺少 @PublicApi 标记", annotation);
        assertTrue(type.getName() + " 的 @PublicApi 说明不应为空", !annotation.value().trim().isEmpty());
    }
}
