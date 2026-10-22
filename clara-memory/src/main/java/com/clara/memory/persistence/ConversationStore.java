package com.clara.memory.persistence;

import com.clara.core.message.IContent;
import com.clara.enums.interaction.HistoryClearScopeEnum;

import java.util.Collections;
import java.util.List;

/**
 * 会话历史单元的持久化契约：按「会话 + 供应商 + 模型」为键读写历史快照。
 *
 * <p>这是 memory 模块内部接口，不暴露为 core 契约；{@code IWindowMemory} 内部依赖它恢复 / 保存历史。
 * 核心约定：{@link #loadSnapshot} 返回快照（版本 + 消息列表），{@link #append} 乐观并发追加，
 * {@link #clear} 清空。版本从 0 开始，每次成功追加递增 1。
 */
public interface ConversationStore {

    /**
     * 读取指定会话历史单元的快照。
     *
     * @param key 会话历史单元键，不可为 null
     * @return 快照，非 null；不存在时返回版本 0、空消息列表
     */
    ConversationSnapshot loadSnapshot(ConversationKey key);

    /**
     * 读取存储中全部会话快照，按最后写入时间从早到晚排列。
     *
     * <p>用途：历史查看需要覆盖「工作目录 .clara 下的全部会话」——历史文件按「会话 + 供应商 + 模型」
     * 分文件存放，只看当前键会漏掉切换模型或 {@code /new} 之前写入的记录。默认实现返回空列表，
     * 表示该存储不支持枚举（例如进程内存储）。
     *
     * @return 全部快照，非 null；不支持枚举时为空列表
     */
    default List<ConversationSnapshot> loadAllSnapshots() {
        return Collections.emptyList();
    }

    /**
     * 乐观并发追加一批消息。
     *
     * @param key             会话历史单元键，不可为 null
     * @param messages        要追加的消息，不可为 null 且不得含 null
     * @param expectedVersion 期望的当前版本，与快照版本不一致时抛版本冲突
     */
    void append(ConversationKey key, List<IContent> messages, long expectedVersion);

    /**
     * 清空指定会话历史单元。
     *
     * @param key 会话历史单元键，不可为 null
     */
    void clear(ConversationKey key);

    /**
     * 按「用户与助手消息」口径清理全部会话历史。
     *
     * <p>为什么由存储实现：历史按「会话 + 供应商 + 模型」分散在多个文件中，只有存储层知道如何按写入时间
     * 排序并原子重写剩余内容；上层只关心「清理了几条聊天记录」。工具消息不计入条数，但当某份历史里的
     * 聊天消息被全部清理时整份删除，避免留下孤立工具消息。
     *
     * @param scope 清理范围，不可为 null
     * @param count 清理条数，{@link HistoryClearScopeEnum#ALL} 时忽略，其余范围必须为正
     * @return 实际清理的聊天消息条数
     * @throws UnsupportedOperationException 当前存储不支持按条清理时
     */
    default int deleteChatMessages(HistoryClearScopeEnum scope, int count) {
        throw new UnsupportedOperationException("当前会话存储不支持清理历史记录");
    }

    /**
     * 释放底层资源。
     */
    void close();
}
