package com.clara.memory.window;

import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.dto.memory.WindowPolicy;
import com.clara.core.message.IContent;
import com.clara.memory.persistence.ConversationKey;
import com.clara.memory.persistence.ConversationSnapshot;
import com.clara.memory.persistence.ConversationStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 默认窗口记忆：以「会话 + 供应商 + 模型」为单位维护短时历史，执行窗口限制与淘汰。
 *
 * <p>历史按供应商与模型隔离；内部依赖 {@link ConversationStore} 恢复 / 保存历史，
 * {@link #window} 只读裁剪、不改变内部存储，{@link #append} 追加、{@link #clear} 清空。
 */
public final class DefaultWindowMemory implements IWindowMemory {

    private final String conversationId;
    private final String provider;
    private final String model;
    private final ConversationStore store;
    private final ConversationKey key;

    private List<IContent> history = new ArrayList<>();
    private long version = 0L;
    private boolean loaded = false;

    /**
     * 创建窗口记忆。
     *
     * @param conversationId 会话标识，不可为空白
     * @param provider       供应商标识，不可为 null
     * @param model          模型标识，不可为 null
     * @param store          底层会话存储，不可为 null
     * @throws IllegalArgumentException 任意参数不合法时
     */
    public DefaultWindowMemory(String conversationId, String provider, String model,
            ConversationStore store) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.conversationId = conversationId;
        this.provider = provider;
        this.model = model;
        this.store = store;
        this.key = new ConversationKey(conversationId, provider, model);
    }

    @Override
    public synchronized String conversationId() {
        return conversationId;
    }

    @Override
    public synchronized void append(List<IContent> messages) {
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        if (messages.isEmpty()) {
            return;
        }
        ensureLoaded();
        List<IContent> copy = new ArrayList<>(messages.size());
        for (IContent message : messages) {
            if (message == null) {
                throw new IllegalArgumentException("messages must not contain null");
            }
            copy.add(message);
        }
        store.append(key, copy, version);
        history.addAll(copy);
        version += 1L;
    }

    @Override
    public synchronized List<IContent> messages() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    @Override
    public synchronized List<IContent> window(WindowPolicy policy, ITokenCountEstimator estimator) {
        ensureLoaded();
        return WindowSupport.trim(history, policy, estimator);
    }

    @Override
    public synchronized void clear() {
        ensureLoaded();
        store.clear(key);
        history = new ArrayList<>();
        version = 0L;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        ConversationSnapshot snapshot = store.loadSnapshot(key);
        history = new ArrayList<>(snapshot.messages());
        version = snapshot.version();
        loaded = true;
    }
}
