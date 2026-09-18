package com.clara.model.provider.compatible.image;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.media.MediaType;
import com.clara.model.api.StreamHandle;
import com.clara.model.api.media.ImageGenerationEvent;
import com.clara.model.api.media.ImageGenerationObserver;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.api.media.ImageGenerationResult;
import com.clara.model.api.media.StreamingImageGenerationModel;
import com.clara.model.provider.compatible.handler.AsyncStreamHandle;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.impl.ProviderInvoker;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.clara.model.utils.ExceptionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 调用 OpenAI 兼容的流式生图接口，并把平台事件转换为统一的图片流事件。
 *
 * <p>用途：请求体置 {@code stream=true} 后提交到执行器异步执行；平台每张图片的产出、失败与整条流的收尾
 * 由 {@link OpenAiCompatibleImageStreamAccumulator} 解析成增量，本类负责落盘并把结果按序推送为
 * {@link ImageGenerationEvent}：每张图一个 {@code IMAGE}（带资产 ID）、单张失败一个 {@code IMAGE_FAILED}，
 * 最后以 {@code RESPONSE_COMPLETE} 或 {@code ERROR} 收尾。
 *
 * <p>与非流式的分工：两者共用配置、平台选项、前置校验（{@link OpenAiCompatibleImageValidator}）与协议
 * 编解码（{@link OpenAiCompatibleImageCodec}），差异只有 {@code stream=true}、{@code Accept} 头与
 * 「逐个事件落盘」还是「整批解析后落盘」。同步调用见 {@link OpenAiCompatibleImageModel}。
 *
 * <p>使用方式（由平台配置派生）：
 * <pre>{@code
 * StreamingImageGenerationModel model = new OpenAiCompatibleStreamingImageModel(
 *         provider.toVolcengineImageConfig("image-fast"), provider.toVolcengineImageOptions("image-fast"),
 *         descriptor, assets, transport, provider.credentialResolver(), executor);
 * StreamHandle handle = model.generate(request, observer);
 * }</pre>
 *
 * <p>执行模型：{@link #generate} 立即返回句柄，真正的网络读取在调用方注入的 {@link ExecutorService} 上
 * 执行；取消会同时取消调用令牌与底层任务。执行器由调用方创建与关闭，本类不接管其生命周期。请求体仍在
 * 调用线程上先编码、先校验，非法请求会在 {@link #generate} 处直接抛出，不会变成异步 ERROR 事件。
 *
 * <p>注意：本类只支持以 JSON 下发请求（含源图 URL）的平台；需要 multipart 上传源图的编辑接口在
 * {@link #generate} 处直接拒绝。
 */
public final class OpenAiCompatibleStreamingImageModel implements StreamingImageGenerationModel {
    /**
     * 图片配置，提供平台、端点、模型 ID、凭据引用与本地限制。
     */
    private final OpenAiCompatibleImageConfig config;
    /**
     * 平台扩展点，负责平台专属字段、限制校验与编码路径选择。
     */
    private final OpenAiCompatibleImageOptions options;
    /**
     * 模型登记项，用于能力校验。
     */
    private final ProviderModelDescriptor descriptor;
    /**
     * 资源桥，负责把平台结果落盘并返回资产 ID。
     */
    private final ProviderMediaAssetBridge assets;
    /**
     * 统一的往返执行器，负责凭据、请求头、发送与状态码校验，与非流式共用同一骨架。
     */
    private final ProviderInvoker invoker;
    /**
     * 协议编解码器，只与配置的平台绑定。
     */
    private final OpenAiCompatibleImageCodec codec;
    /**
     * 承载阻塞式流读取的执行器，由调用方创建与关闭。
     */
    private final ExecutorService executor;

    /**
     * 创建流式兼容图片模型。
     *
     * @param config     图片配置，不可为 null
     * @param options    平台扩展点，不可为 null
     * @param descriptor 模型登记项，平台与模型 ID 必须与配置一致
     * @param assets     资源桥，不可为 null
     * @param transport  HTTP 传输器，需支持 SSE，不可为 null
     * @param credentials 凭据解析器，不可为 null
     * @param executor   承载流读取的执行器，不可为 null，生命周期由调用方管理
     * @throws IllegalArgumentException 依赖为 null、描述符与配置不匹配时
     */
    public OpenAiCompatibleStreamingImageModel(OpenAiCompatibleImageConfig config, OpenAiCompatibleImageOptions options,
            ProviderModelDescriptor descriptor, ProviderMediaAssetBridge assets, ProviderHttpTransport transport,
            ProviderCredentialResolver credentials, ExecutorService executor) {
        if (config == null || options == null || descriptor == null || assets == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        if (descriptor.getProvider() != config.getProvider()
                || !config.getModelId().equals(descriptor.getModelId())) {
            throw new IllegalArgumentException("descriptor does not match config");
        }
        this.config = config;
        this.options = options;
        this.descriptor = descriptor;
        this.assets = assets;
        this.invoker = new ProviderInvoker(config.getProvider(), config.getSecretRef(), transport, credentials);
        this.codec = new OpenAiCompatibleImageCodec(config);
        this.executor = executor;
    }

    /**
     * 提交一次流式生图调用。
     *
     * <p>立即返回句柄，实际的网络读取、落盘与事件推送在执行器线程中完成；每张图片产出后立即推送
     * {@code IMAGE}，最后以 {@code RESPONSE_COMPLETE} 或 {@code ERROR} 作为唯一终态事件收尾。
     *
     * @param request  图片请求，不可为 null
     * @param observer 图片流事件观察者，不可为 null
     * @return 可用于查询完成状态或取消的句柄
     * @throws IllegalArgumentException 任一参数为 null、平台选项与配置不匹配时
     * @throws com.clara.kernel.ClaraException 模型不具备所需能力、超出本地限制、平台不支持该请求，
     *                                         或该平台必须走 multipart 上传时
     */
    public StreamHandle generate(final ImageGenerationRequest request, final ImageGenerationObserver observer) {
        if (request == null || observer == null) {
            throw new IllegalArgumentException("request and observer must not be null");
        }
        OpenAiCompatibleImageValidator.validate(config, options, descriptor, request);
        if (options.usesMultipartEdit(request)) {
            throw ExceptionUtils.capabilityUnsupported();
        }
        request.getContext().getCancellationToken().throwIfCancellationRequested(request.getContext().getTraceId());
        final byte[] payload = codec.encodeJson(request, config, options, assets, true);
        final AsyncStreamHandle handle = new AsyncStreamHandle(request.getContext());
        handle.setFuture(executor.submit(() -> execute(request, observer, payload)));
        return handle;
    }

    /**
     * 在执行器线程中完成一次完整的流式调用。
     *
     * <p>把每个事件交给累加器：单张图片立即落盘并推送，单张失败只推送事件不中断流，收尾事件推送汇总结果
     * 并结束读取；任何异常都会转换为 ERROR 事件。
     *
     * @param request  图片请求
     * @param observer 图片流事件观察者
     * @param payload  已在调用线程编码好的请求体
     */
    private void execute(final ImageGenerationRequest request, final ImageGenerationObserver observer, byte[] payload) {
        final ImageEventEmitter emitter = new ImageEventEmitter(observer);
        final List<String> assetIds = new ArrayList<String>();
        try {
            final OpenAiCompatibleImageStreamAccumulator accumulator =
                    new OpenAiCompatibleImageStreamAccumulator(config);
            invoker.exchangeStreaming(request, request.getContext(),
                    new OpenAiCompatibleImageStreamPlan(config, payload), event -> {
                        OpenAiCompatibleImageStreamAccumulator.Chunk chunk = accumulator.accept(event.getData());
                        switch (chunk.getKind()) {
                            case IMAGE:
                                String assetId = store(chunk.getOutput(), request);
                                assetIds.add(assetId);
                                emitter.image(chunk.getImageIndex(), assetId);
                                return true;
                            case IMAGE_FAILED:
                                emitter.imageFailed(chunk.getImageIndex(), chunk.getError());
                                return true;
                            default:
                                finish(emitter, assetIds, accumulator, chunk);
                                return false;
                        }
                    });
            if (!accumulator.isComplete()) {
                throw ExceptionUtils.protocolError(config.getProvider());
            }
        } catch (ClaraException exception) {
            emitter.error(exception.getError());
        } catch (RuntimeException exception) {
            emitter.error(ExceptionUtils.protocolError(config.getProvider()).getError());
        }
    }

    /**
     * 处理收尾事件：有图片落盘就推送汇总结果，一张都没有则按失败收尾。
     *
     * <p>单张失败的原因已在 {@code IMAGE_FAILED} 事件里逐个下发，因此这里不再重复携带，只给出集合层面的失败。
     *
     * @param emitter     事件发射器
     * @param assetIds    已落盘的资产 ID
     * @param accumulator 累加器，提供平台请求标识
     * @param chunk       收尾增量
     */
    private void finish(ImageEventEmitter emitter, List<String> assetIds,
            OpenAiCompatibleImageStreamAccumulator accumulator, OpenAiCompatibleImageStreamAccumulator.Chunk chunk) {
        if (assetIds.isEmpty()) {
            emitter.error(ExceptionUtils.providerEventFailure(config.getProvider(), null).getError());
            return;
        }
        Map<String, String> metadata = new LinkedHashMap<String, String>(chunk.getMetadata());
        metadata.put("provider", config.getProvider().name());
        metadata.put("model", config.getModelId());
        emitter.complete(new ImageGenerationResult(assetIds, accumulator.getRequestId(), metadata));
    }

    /**
     * 把一条输出落盘并返回资产 ID。
     *
     * @param output  输出，可能是内联字节或远程 URL
     * @param request 图片请求，提供调用上下文
     * @return 资产 ID
     */
    private String store(OpenAiCompatibleImageCodec.Output output, ImageGenerationRequest request) {
        return output.isInline()
                ? assets.storeBytes(output.getBytes(), MediaType.IMAGE, output.getMimeType(), request.getContext())
                : assets.storeRemote(output.getUrl(), MediaType.IMAGE, output.getMimeType(), request.getContext());
    }

    /**
     * 按序号向观察者发射图片流事件，并保证终态事件只发一次。
     *
     * <p>事件序号从 1 开始递增；{@code RESPONSE_COMPLETE} 与 {@code ERROR} 发射后发射器进入终态，后续调用
     * 一律忽略；观察者自身抛异常时也把发射器置为终态，避免异常处理路径再次触发回调。
     */
    private static final class ImageEventEmitter {
        /**
         * 事件接收方，由调用方提供。
         */
        private final ImageGenerationObserver observer;
        /**
         * 事件序号，从 1 开始递增。
         */
        private long sequence;
        /**
         * 是否已发出终态事件（完成或错误）。
         */
        private boolean terminal;

        private ImageEventEmitter(ImageGenerationObserver observer) {
            this.observer = observer;
        }

        /**
         * 发射「单张图片已落盘」事件。
         *
         * @param imageIndex 图片在组图中的位置
         * @param assetId    资产 ID
         */
        private void image(int imageIndex, String assetId) {
            emit(new ImageGenerationEvent(ImageGenerationEvent.Type.IMAGE, ++sequence, imageIndex, assetId, null, null));
        }

        /**
         * 发射「单张图片失败」事件。
         *
         * @param imageIndex 图片在组图中的位置
         * @param error      失败原因
         */
        private void imageFailed(int imageIndex, ClaraError error) {
            emit(new ImageGenerationEvent(ImageGenerationEvent.Type.IMAGE_FAILED, ++sequence, imageIndex, null, null, error));
        }

        /**
         * 发射「本次请求完成」终态事件。
         *
         * @param result 汇总结果
         */
        private void complete(ImageGenerationResult result) {
            emit(new ImageGenerationEvent(ImageGenerationEvent.Type.RESPONSE_COMPLETE, ++sequence, 0, null, result, null));
        }

        /**
         * 发射「整条流失败」终态事件。
         *
         * @param error 错误信息
         */
        private void error(ClaraError error) {
            if (terminal) {
                return;
            }
            try {
                emit(new ImageGenerationEvent(ImageGenerationEvent.Type.ERROR, ++sequence, 0, null, null, error));
            } catch (RuntimeException ignored) {
                terminal = true;
            }
        }

        /**
         * 发射事件并维护终态标记。
         *
         * @param event 待发射事件
         */
        private void emit(ImageGenerationEvent event) {
            if (terminal) {
                return;
            }
            if (event.getType() == ImageGenerationEvent.Type.RESPONSE_COMPLETE
                    || event.getType() == ImageGenerationEvent.Type.ERROR) {
                terminal = true;
            }
            observer.onEvent(event);
        }
    }
}
