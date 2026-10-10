package com.clara.orchestration.capability;

import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.api.IStreamModel;
import com.clara.core.model.IResponse;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.ImageItem;
import com.clara.kernel.stream.IStreamHandle;
import com.clara.kernel.stream.IStreamObserver;
import com.clara.orchestration.OrchestrationErrors;

import java.util.ArrayList;
import java.util.List;

/**
 * 多模态能力分派器：统一走 {@link IStreamModel} 调用入口，按能力种类选择请求构建与结果提取方式。
 *
 * <p>CHAT 走对话链路；IMAGE / VIDEO / AUDIO 走各自请求构建与结果提取。所有能力共用同一调用入口，因此
 * 调用记录与取消语义一致。
 */
public final class CapabilityDispatcher {

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
        IStreamHandle handle = streamModel.chat(request, HttpResponse.class, observer);
        IResponse result = handle.result();
        return result instanceof HttpResponse ? (HttpResponse) result : null;
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
        List<String> references = new ArrayList<String>();
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
