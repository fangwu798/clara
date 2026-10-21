package com.clara.core.interaction;

import java.util.List;

/**
 * 执行期用户补充信息的读取端：编排层在每次请求模型前取走已提交的补充内容。
 *
 * <p>与 {@link IUserInterventionSink} 分离，是为了让生产者（控制台监听线程）只依赖写入能力，
 * 消费者（编排层）只依赖读取能力，避免把缓冲区实现暴露给业务代码。
 */
public interface IUserInterventionSource {

    /**
     * 是否还有尚未消费的补充内容。
     *
     * @return 存在待消费内容时返回 true
     */
    boolean hasPending();

    /**
     * 取走并清空当前全部补充内容，按提交顺序返回。
     *
     * @return 非 null 列表；没有内容时为空列表
     */
    List<String> drain();
}
