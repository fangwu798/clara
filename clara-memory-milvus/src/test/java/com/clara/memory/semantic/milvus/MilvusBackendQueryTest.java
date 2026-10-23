package com.clara.memory.semantic.milvus;

import com.clara.dto.memory.SemanticMemoryQuery;
import com.clara.enums.memory.MemorySourceTypeEnum;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * {@link MilvusBackendQuery} 的单元测试：查询封装为纯逻辑，不依赖 Milvus 连接。
 */
public class MilvusBackendQueryTest {

    @Test
    public void holdsQueryAndCandidateLimit() {
        SemanticMemoryQuery query = SemanticMemoryQuery.builder(new float[] {1.0f, 0.0f}, 3, 0.7d, 1234L)
                .sourceType(MemorySourceTypeEnum.CONVERSATION)
                .conversationId("conv-1")
                .build();
        MilvusBackendQuery backendQuery = new MilvusBackendQuery(query, 5);
        assertNotNull(backendQuery.getQuery());
        assertEquals(query, backendQuery.getQuery());
        assertEquals(5, backendQuery.getCandidateLimit());
    }

    @Test
    public void rejectsInvalidCandidateLimit() {
        SemanticMemoryQuery query = SemanticMemoryQuery.builder(new float[] {0.0f, 1.0f}, 1, 0.5d, 1234L)
                .sourceType(MemorySourceTypeEnum.PROMPT)
                .build();
        try {
            new MilvusBackendQuery(query, 0);
            fail("candidateLimit <= 0 should be rejected");
        } catch (IllegalArgumentException expected) {
            // 预期：非正候选上限不可用
        }
    }
}
