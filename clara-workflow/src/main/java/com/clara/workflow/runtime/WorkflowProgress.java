package com.clara.workflow.runtime;

import com.clara.core.model.IStreamObserver;

import java.util.function.Consumer;

/**
 * 工作流节点执行期的「当轮输出通道」：把当前对话轮的流式观察者与提示输出注册到线程，供工作流 AGENT 节点复用。
 *
 * <p>是什么、在哪一层：clara-workflow 的线程局部上下文（包 {@code com.clara.workflow.runtime}）。
 *
 * <p>为什么需要：工作流节点调用模型时是同步、无流式渲染的，用户在长耗时节点（如图片生成约 1 分钟）期间看不到
 * 任何过程，文本类节点的思考也不可见。把当轮对话已经创建的流式观察者透传进来，文本类节点即可复用同一观察者
 * 实时显示思考 / 正文；同时提供一个提示通道，让各节点输出「正在做什么」的进度行。
 *
 * <p>为什么用线程局部：工作流的顺序节点在调用线程上执行（与当轮对话同线程），可直接取到；并行分支在引擎线程池
 * 执行，取不到时退化为直接写标准输出（仅落日志）。
 *
 * <p>生命周期：由宿主（{@code ClaraApplication}）在每轮对话期间用 {@link #open} 打开、退出时通过
 * {@link Scope#close()} 还原；未打开时 {@link #observer()} 返回 null、{@link #notice(String)} 写标准输出。
 */
public final class WorkflowProgress {

    /**
     * 当前线程注册的通道；未注册时为 null。
     */
    private static final ThreadLocal<WorkflowProgress> CURRENT = new ThreadLocal<WorkflowProgress>();

    private final IStreamObserver observer;
    private final Consumer<String> notice;

    private WorkflowProgress(IStreamObserver observer, Consumer<String> notice) {
        this.observer = observer;
        this.notice = notice;
    }

    /**
     * 打开当轮通道作用域：进入时注册，退出时还原上一层的注册（支持嵌套）。
     *
     * @param observer 当轮流式观察者，可为 null（不显示思考 / 正文）
     * @param notice   提示输出（如控制台），可为 null（退化为写标准输出）
     * @return 作用域句柄，非 null
     */
    public static Scope open(IStreamObserver observer, Consumer<String> notice) {
        Scope scope = new Scope(CURRENT.get());
        CURRENT.set(new WorkflowProgress(observer, notice));
        return scope;
    }

    /**
     * 当前线程的流式观察者。
     *
     * @return 观察者；未注册时为 null
     */
    public static IStreamObserver observer() {
        WorkflowProgress current = CURRENT.get();
        return current == null ? null : current.observer;
    }

    /**
     * 输出一条节点进度。
     *
     * @param text 进度文本，非 null
     */
    public static void notice(String text) {
        WorkflowProgress current = CURRENT.get();
        if (current != null && current.notice != null) {
            current.notice.accept(text);
            return;
        }
        System.out.println(text);
    }

    /**
     * 作用域句柄：关闭时把当前线程的注册还原为打开前的状态。
     */
    public static final class Scope implements AutoCloseable {

        private final WorkflowProgress previous;
        private boolean closed;

        private Scope(WorkflowProgress previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
