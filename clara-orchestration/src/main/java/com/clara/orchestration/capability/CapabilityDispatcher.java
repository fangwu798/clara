package com.clara.orchestration.capability;

import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.StreamEventEnum;
import com.clara.core.model.IStreamModel;
import com.clara.core.model.IResponse;
import com.clara.dto.model.StreamEvent;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.ImageItem;
import com.clara.core.model.IStreamHandle;
import com.clara.core.model.IStreamObserver;
import com.clara.orchestration.OrchestrationErrors;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 多模态能力分派器：统一走 {@link IStreamModel} 调用入口，按能力种类选择请求构建与结果提取方式。
 *
 * <p>CHAT 走对话链路；IMAGE / VIDEO / AUDIO 走各自请求构建与结果提取。所有能力共用同一调用入口，因此
 * 调用记录与取消语义一致。
 */
public final class CapabilityDispatcher {

    /**
     * 等待流终态的最长时长（毫秒），作为兜底避免流异常不结束时无限阻塞；取值不小于传输层读超时。
     */
    private static final long STREAM_TERMINAL_MAX_WAIT_MILLIS = 900_000L;

    private final IStreamModel streamModel;

    /**
     * 创建能力分派器。
     *
     * @param streamModel 统一模型调用入口，不可为 null
     */
    public CapabilityDispatcher(IStreamModel streamModel) {
        if (streamModel == null) {
            throw new IllegalArgumentException("streamModel must not be null");
        }
        this.streamModel = streamModel;
    }

    /**
     * 以同步方式执行一次能力调用。
     *
     * @param request 请求对象，不可为 null
     * @return 响应对象，非 null
     */
    public HttpResponse invoke(HttpRequest request) {
        return streamModel.chat(request, HttpResponse.class);
    }

    /**
     * 执行一次能力调用：观察者非 null 时走流式，否则退化为同步。
     *
     * @param request  请求对象，不可为 null
     * @param observer 领域事件接收方，可为 null
     * @return 累积响应对象；流式失败或取消时可能为 null
     */
    public HttpResponse invoke(HttpRequest request, IStreamObserver observer) {
        if (observer == null) {
            return invoke(request);
        }
        CountDownLatch terminal = new CountDownLatch(1);
        IStreamHandle handle = streamModel.chat(request, HttpResponse.class, event -> {
            try {
                observer.onEvent(event);
            } finally {
                if (isTerminal(event)) {
                    terminal.countDown();
                }
            }
        });
        awaitTerminal(handle, terminal);
        IResponse result = handle.result();
        return result instanceof HttpResponse ? (HttpResponse) result : null;
    }

    /**
     * 等待流进入终态后再取结果。
     *
     * <p>流式读取在后台线程执行（见 {@code StreamHandle.start()}），入口返回句柄时读数尚未完成；
     * 因此这里阻塞等待终态事件，避免在读取未完成时 {@code result()} 恒为 null。使用
     * {@link CountDownLatch} 而非轮询睡眠，等待期间不占用 CPU。
     *
     * @param handle 流式句柄，非 null
     * @param terminal 终态信号，收到 {@code RESPONSE_COMPLETE} 或 {@code ERROR} 时放行
     */
    private static void awaitTerminal(IStreamHandle handle, CountDownLatch terminal) {
        if (handle.isDone()) {
            return;
        }
        try {
            if (!terminal.await(STREAM_TERMINAL_MAX_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                handle.cancel();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 判断事件是否为终态事件。
     *
     * @param event 流事件，可为 null
     * @return {@code RESPONSE_COMPLETE} 或 {@code ERROR} 时返回 true
     */
    private static boolean isTerminal(StreamEvent event) {
        return event != null && (event.getType() == StreamEventEnum.RESPONSE_COMPLETE
                || event.getType() == StreamEventEnum.ERROR);
    }

    /**
     * 按能力种类从响应中提取结果内容。
     *
     * @param kind     能力种类，不可为 null
     * @param response 模型响应，可为 null
     * @return 提取到的结果文本；无可用结果时为 null
     */
    public String extract(CapabilityKindEnum kind, HttpResponse response) {
        if (kind == null) {
            throw OrchestrationErrors.invalidArgument("capabilityKind must not be null");
        }
        if (response == null) {
            return null;
        }
        if (kind == CapabilityKindEnum.CHAT) {
            return response.getContent();
        }
        if (kind == CapabilityKindEnum.IMAGE) {
            return joinImages(response.getImages());
        }
        return response.getContent();
    }

    private static String joinImages(List<ImageItem> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        List<String> references = new ArrayList<>();
        for (ImageItem image : images) {
            if (image == null) {
                continue;
            }
            if (image.getUrl() != null) {
                references.add(image.getUrl());
            } else if (image.getB64Json() != null) {
                references.add(image.getB64Json());
            }
        }
        return references.isEmpty() ? null : String.join("\n", references);
    }
}
