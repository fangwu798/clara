package com.clara.memory.shortterm;

import com.clara.model.api.ChatMessage;
import com.clara.model.api.Content;
import com.clara.model.api.Role;
import com.clara.model.api.ToolCallContent;
import java.util.Iterator;
import java.util.List;

/**
 * 提供窗口式对话记忆共用的计算逻辑。
 */
final class WindowSupport {
    private WindowSupport() {
    }

    static boolean addOrReplaceSystem(List<ChatMessage> messages, ChatMessage message,
            boolean alwaysKeepSystemFirst) {
        if (message.getRole() != Role.SYSTEM) {
            messages.add(message);
            return true;
        }
        for (Iterator<ChatMessage> iterator = messages.iterator(); iterator.hasNext();) {
            ChatMessage existing = iterator.next();
            if (existing.getRole() == Role.SYSTEM) {
                if (existing.equals(message)) return false;
                iterator.remove();
            }
        }
        if (alwaysKeepSystemFirst) messages.add(0, message);
        else messages.add(message);
        return true;
    }

    static void normalizeSystems(List<ChatMessage> messages, boolean alwaysKeepSystemFirst) {
        ChatMessage lastSystem = null;
        int nonSystemMessagesBeforeLast = 0;
        int nonSystemCount = 0;
        for (ChatMessage message : messages) {
            if (message == null) { throw new IllegalArgumentException("messages must not contain null"); }
            if (message.getRole() == Role.SYSTEM) {
                lastSystem = message;
                nonSystemMessagesBeforeLast = nonSystemCount;
            } else {
                nonSystemCount++;
            }
        }
        if (lastSystem == null) return;
        for (Iterator<ChatMessage> iterator = messages.iterator(); iterator.hasNext();) {
            if (iterator.next().getRole() == Role.SYSTEM) iterator.remove();
        }
        int index = alwaysKeepSystemFirst ? 0 : nonSystemMessagesBeforeLast;
        messages.add(index, lastSystem);
    }

    static void evictAt(List<ChatMessage> messages, int index) {
        ChatMessage evicted = messages.remove(index);
        if (!hasToolCalls(evicted)) return;
        while (index < messages.size() && messages.get(index).getRole() == Role.TOOL) {
            messages.remove(index);
        }
    }

    static int evictionIndex(List<ChatMessage> messages) {
        return messages.get(0).getRole() == Role.SYSTEM ? 1 : 0;
    }

    private static boolean hasToolCalls(ChatMessage message) {
        if (message.getRole() != Role.ASSISTANT) return false;
        for (Content content : message.getContents()) {
            if (content instanceof ToolCallContent) return true;
        }
        return false;
    }
}
