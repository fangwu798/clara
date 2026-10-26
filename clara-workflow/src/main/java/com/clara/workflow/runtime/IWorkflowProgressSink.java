package com.clara.workflow.runtime;

/**
 * 工作流节点执行期的输出端口：普通进度、角色发言与当前发言人。
 *
 * <p>是什么、在哪一层：clara-workflow 运行时的输出契约，由宿主实现（CLI 用控制台、其它宿主可接日志或 SSE）。
 *
 * <p>为什么分三类：{@code notice} 是步骤进度（「第 1/3 轮 architect 发言中…」）；
 * {@code statement} 是某个角色的完整发言，宿主按角色着色，让多角色讨论一眼能区分；
 * {@code speaking} 是「此刻谁在说、用的是哪个模型」，宿主据此实时更新底部状态行。
 *
 * @since 1.0.0
 */
public interface IWorkflowProgressSink {

    /**
     * 输出一条普通进度。
     *
     * @param text 进度文本，非 null
     */
    void notice(String text);

    /**
     * 输出某个角色的发言（一整段，可含换行）。
     *
     * <p>默认实现退化为普通进度，宿主不关心角色区分时也能正常显示。
     *
     * @param speaker 发言人（角色名），非 null
     * @param text    发言正文（含标题），非 null
     */
    default void statement(String speaker, String text) {
        notice(text);
    }

    /**
     * 当前发言人发生变化。
     *
     * @param speaker 发言人（角色名）；null 表示讨论结束、恢复默认状态
     * @param model   该发言角色使用的模型标识，可为 null
     */
    default void speaking(String speaker, String model) {
        // 默认宿主不展示当前发言人
    }
}
