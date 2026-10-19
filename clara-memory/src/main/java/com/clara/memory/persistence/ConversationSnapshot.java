package com.clara.memory.persistence;

import com.clara.core.message.IContent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话历史单元的快照：乐观并发版本号 + 消息列表。
 *
 * <p>不可变值对象，{@link #messages()} 返回不可变列表。
 */
public final class ConversationSnapshot {

    private final long version;
    private final List<IContent> messages;

    /**
     * 创建会话快照。
     *
     * @param version  版本号，不可为负；0 表示空 / 不存在
     * @param messages 消息列表，不可为 null 且不得含 null
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public ConversationSnapshot(long version, List<IContent> messages) {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (messages == null) {
            throw new IllegalArgumentException("messages must not be null");
        }
        List<IContent> copy = new ArrayList<>(messages.size());
        for (IContent message : messages) {
            if (message == null) {
                throw new IllegalArgumentException("messages must not contain null");
            }
            copy.add(message);
        }
        this.version = version;
        this.messages = Collections.unmodifiableList(copy);
    }

    public long version() {
        return version;
    }

    public List<IContent> messages() {
        return messages;
    }
}
