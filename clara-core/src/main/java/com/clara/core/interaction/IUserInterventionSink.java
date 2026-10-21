package com.clara.core.interaction;

/**
 * 执行期用户补充信息的写入端：宿主在模型思考或工具执行期间收集用户输入，交给本轮编排消费。
 *
 * <p>为什么单独定义：补充信息不是新的对话轮次，也不应打断正在进行的模型调用；写入端只负责把文本放入
 * 线程安全的缓冲区，消费时机（下一次模型请求前、工具往返结束后）由编排层决定。
 *
 * <p>实现要求：允许多个线程同时调用 {@link #offer(String)}，不得阻塞调用线程。
 */
public interface IUserInterventionSink {

    /**
     * 提交一条补充说明；空文本应被忽略。
     *
     * @param text 用户补充内容，可为 null
     */
    void offer(String text);
}
