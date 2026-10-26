package com.clara.adapter.console;

import com.clara.workflow.runtime.IWorkflowProgressSink;

/**
 * 把工作流节点的输出接到控制台：进度灰字、角色发言按角色着色、底部状态行实时显示当前角色与模型。
 *
 * <p>是什么、在哪一层：application 的控制台适配（{@code com.clara.adapter.console}），实现 clara-workflow
 * 的 {@link IWorkflowProgressSink}；工作流模块不感知控制台与颜色，着色与状态行都在这一层决定。
 */
public final class ConsoleWorkflowProgressSink implements IWorkflowProgressSink {

    /**
     * 控制台视图，非 null。
     */
    private final ConsoleView view;

    /**
     * 本轮流式观察者：输出整段文本前先收尾未结束的流式段，避免粘行。
     */
    private final ConsoleView.ConsoleStreamObserver observer;

    /**
     * 创建控制台输出端口。
     *
     * @param view     控制台视图，非 null
     * @param observer 本轮流式观察者，可为 null
     */
    public ConsoleWorkflowProgressSink(ConsoleView view, ConsoleView.ConsoleStreamObserver observer) {
        this.view = view;
        this.observer = observer;
    }

    @Override
    public void notice(String text) {
        endStream();
        view.emit(text, ConsoleView.ANSI_GRAY);
    }

    @Override
    public void statement(String speaker, String text) {
        endStream();
        view.emitSpeaker(speaker, text);
    }

    @Override
    public void speaking(String speaker, String model) {
        view.setSpeakerStatus(speaker, model);
    }

    /**
     * 收尾当前流式段：整段输出与流式正文之间需要有明确的分行边界。
     */
    private void endStream() {
        if (observer != null) {
            observer.endStream();
        }
    }
}
