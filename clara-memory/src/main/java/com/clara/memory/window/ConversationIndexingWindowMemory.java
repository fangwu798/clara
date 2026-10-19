package com.clara.memory.window;

import com.clara.core.memory.ConversationMemoryText;
import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.memory.WindowPolicy;
import com.clara.enums.memory.MemorySourceTypeEnum;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话索引窗口记忆：在既有窗口记忆之上，把每条追加的对话记录向量化后写入语义记忆存储。
 *
 * <p>是什么、在哪一层：{@code clara-memory} 的 {@link IWindowMemory} 装饰器（{@code com.clara.memory.window}），
 * 由装配层在「启用外部向量库（Milvus）」时包一层；未启用时不包装，行为与原来完全一致。
 *
 * <p>为什么需要：窗口记忆只保留「当前会话最近的历史」，关掉进程就只剩落盘的历史文件；跨会话/长程关联要靠语义
 * 记忆。本装饰器把每条消息按会话写入语义库（来源 {@code CONVERSATION}），配合
 * {@code SemanticRecallCoordinator} 的按会话检索，即构成「每轮召回被淘汰的历史」。
 *
 * <p>条目标识：{@code CONVERSATION:<会话>:<序号>}，序号取「本批消息在完整历史中的位置」，因此同一会话重启后
 * 继续追加也不会与已有条目冲突或重复。
 *
 * <p>失败语义：写入语义库失败（如向量库不可用）只提示到标准错误并继续——向量化是附加能力，不该让本轮对话失败。
 */
public final class ConversationIndexingWindowMemory implements IWindowMemory {

    private final IWindowMemory delegate;
    private final IEmbeddingClient embeddingClient;
    private final ISemanticMemoryStore memoryStore;

    /**
     * 创建对话索引窗口记忆。
     *
     * @param delegate        被装饰的窗口记忆，不可为 null
     * @param embeddingClient 向量化客户端，不可为 null
     * @param memoryStore     语义记忆存储，不可为 null
     * @throws IllegalArgumentException 任一参数为 null 时
     */
    public ConversationIndexingWindowMemory(IWindowMemory delegate, IEmbeddingClient embeddingClient,
            ISemanticMemoryStore memoryStore) {
        if (delegate == null || embeddingClient == null || memoryStore == null) {
            throw new IllegalArgumentException("conversation indexing dependencies must not be null");
        }
        this.delegate = delegate;
        this.embeddingClient = embeddingClient;
        this.memoryStore = memoryStore;
    }

    @Override
    public String conversationId() {
        return delegate.conversationId();
    }

    @Override
    public void append(List<IContent> messages) {
        if (messages == null || messages.isEmpty()) {
            delegate.append(messages);
            return;
        }
        // 先取「本批之前的条数」，使条目序号与完整历史位置一致；再落历史，最后写向量库。
        int offset = delegate.messages().size();
        delegate.append(messages);
        index(messages, offset);
    }

    @Override
    public List<IContent> messages() {
        return delegate.messages();
    }

    @Override
    public List<IContent> window(WindowPolicy policy, ITokenCountEstimator estimator) {
        return delegate.window(policy, estimator);
    }

    @Override
    public void clear() {
        delegate.clear();
        try {
            memoryStore.deleteByConversation(conversationId());
        } catch (RuntimeException failure) {
            warn(failure);
        }
    }

    /**
     * 把一批消息写入语义记忆；失败的条目跳过，整体失败只提示不抛出。
     */
    private void index(List<IContent> messages, int offset) {
        List<MemoryEntry> entries = new ArrayList<>(messages.size());
        long now = System.currentTimeMillis();
        for (int position = 0; position < messages.size(); position++) {
            IContent message = messages.get(position);
            String content = ConversationMemoryText.of(message);
            if (content == null) {
                continue;
            }
            entries.add(new MemoryEntry(entryId(offset + position), MemorySourceTypeEnum.CONVERSATION,
                    conversationId(), conversationId(), content, sha256(content),
                    embeddingClient.embed(content), now, 0L, null));
        }
        if (entries.isEmpty()) {
            return;
        }
        try {
            memoryStore.upsert(entries);
        } catch (RuntimeException failure) {
            warn(failure);
        }
    }

    private String entryId(int position) {
        return MemorySourceTypeEnum.CONVERSATION.name() + ":" + conversationId() + ":" + position;
    }

    private static String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                String hex = Integer.toHexString(value & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 not available", failure);
        }
    }

    private void warn(RuntimeException failure) {
        System.err.println("[memory] 对话记录写入语义记忆失败（本轮继续）：" + failure.getMessage());
    }
}
