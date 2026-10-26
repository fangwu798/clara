package com.clara.memory.semantic.milvus;

import com.clara.core.diagnostics.Diagnostics;
import com.clara.memory.semantic.spi.ISemanticMemoryBackendProvider;
import com.clara.memory.semantic.spi.SemanticMemoryBackend;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Milvus 语义记忆后端提供者：通过 {@link java.util.ServiceLoader} 注册，由 SDK 装配时按需发现。
 *
 * <p>未配置 {@code milvus.enabled}、配置非法或连接失败时返回 {@code null}，装配方回退到进程内实现。
 * 这样主库不依赖 Milvus SDK，只有显式引入本模块的宿主才会加载相关类。
 */
public final class MilvusSemanticMemoryProvider implements ISemanticMemoryBackendProvider {

    /**
     * Milvus SDK 的 JUL 日志器：必须由本类强引用持有，否则 LogManager 的弱引用会让层级设置失效。
     */
    private static final Logger MILVUS_SDK_LOGGER = Logger.getLogger("io.milvus");

    @Override
    public SemanticMemoryBackend open(Path claraConfig, int defaultDimension, String projectName) {
        MilvusSettings settings;
        try {
            settings = MilvusSettings.load(claraConfig, defaultDimension, projectName);
        } catch (IllegalArgumentException invalid) {
            // 配置写错（端口非数字、集合名为空等）与「没配」一样，都只降级、不阻断应用启动。
            Diagnostics.warn("[memory] Milvus 配置非法，语义记忆回退为进程内实现：" + invalid.getMessage());
            return null;
        }
        if (settings == null) {
            return null;
        }
        quietMilvusLogging();
        warnOnBm25NonUtf8Charset(settings);
        MilvusSemanticMemoryStore store = connect(settings);
        return store == null ? null : new SemanticMemoryBackend(store, describe(settings));
    }

    /**
     * 连接 Milvus：构造期即校验集合存在且维度一致。
     *
     * <p>连不上时给出原因并返回 {@code null}，配了向量库但没起服务不该让整个应用起不来。
     *
     * @param settings Milvus 配置，非 null
     * @return 语义记忆存储；不可用时 null
     */
    private static MilvusSemanticMemoryStore connect(MilvusSettings settings) {
        MilvusBackend backend = null;
        boolean handedOff = false;
        try {
            MilvusCollectionSpec spec = collectionSpec(settings);
            backend = MilvusBackendFactory.create(new MilvusBackendConfig(
                    settings.getHost(), settings.getPort(), settings.getUsername(), settings.getPassword(),
                    MilvusBackendConfig.DEFAULT_DATABASE,
                    MilvusBackendConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                    MilvusBackendConfig.DEFAULT_IDLE_TIMEOUT_MILLIS), spec);
            MilvusSemanticMemoryStore store = createStore(spec, backend, settings);
            handedOff = true;
            return store;
        } catch (RuntimeException unavailable) {
            Diagnostics.warn("[memory] Milvus 不可用，语义记忆回退为进程内实现（本次不写对话记录）："
                    + unavailable.getMessage());
            return null;
        } finally {
            if (!handedOff && backend != null) {
                closeQuietly(backend);
            }
        }
    }

    private static MilvusSemanticMemoryStore createStore(MilvusCollectionSpec spec, MilvusBackend backend,
                                                         MilvusSettings settings) {
        try {
            return new MilvusSemanticMemoryStore(spec, backend);
        } catch (RuntimeException mismatch) {
            if (!settings.isRecreateOnSchemaMismatch()) {
                throw mismatch;
            }
            // 旧集合结构与当前配置不一致（切了检索模式 / 换了嵌入维度）：旧向量与新查询不可比，重建是唯一选项。
            // 只在配置里显式开了重建才动手（删集合会丢旧记录）；重建失败会被外层兜回进程内实现。
            Diagnostics.warn("[memory] Milvus 集合结构与配置不匹配，按配置重建：" + mismatch.getMessage());
            backend.dropCollection(spec.getCollection());
            return new MilvusSemanticMemoryStore(spec, backend);
        }
    }

    private static void closeQuietly(MilvusBackend backend) {
        try {
            backend.close();
        } catch (RuntimeException closeFailure) {
            // 关闭失败不应覆盖原始的连接失败原因，调用方已经回退到进程内实现。
        }
    }

    /**
     * BM25 模式下检查 JVM 默认字符集：不是 UTF-8 时打印明确警告。
     *
     * <p>为什么必须提醒：milvus-sdk-java 拼 VarChar 全文检索占位符时用的是 {@code String.getBytes()}（平台
     * 默认字符集），不是 UTF-8。中文 Windows 上未加 {@code -Dfile.encoding=UTF-8} 时默认字符集是 GBK，
     * 中文正文会以 GBK 字节发出，Milvus 的分词器收到非法 UTF-8 会 panic 并让服务端整个 abort——代价不是
     * 这一次检索失败，而是整个记忆库掉线。启动脚本 clara.bat / clara.sh 已带该参数，只有手工 java -jar 才会踩到。
     *
     * @param settings Milvus 配置，非 null
     */
    private static void warnOnBm25NonUtf8Charset(MilvusSettings settings) {
        if (!settings.isBm25Mode()) {
            return;
        }
        Charset charset = Charset.defaultCharset();
        if (!StandardCharsets.UTF_8.equals(charset)) {
            Diagnostics.warn("[memory] 警告：JVM 默认字符集是 " + charset.name()
                    + "，Milvus 全文检索的正文会按它编码，可能让 Milvus 服务端 abort；"
                    + "请用 clara.bat / clara.sh 启动（已带 -Dfile.encoding=UTF-8）。");
        }
    }

    /**
     * 按配置构造集合规格：BM25 模式用分词器定义集合身份（不存向量），向量模式用维度。
     *
     * @param settings Milvus 配置，非 null
     * @return 集合规格，非 null
     */
    private static MilvusCollectionSpec collectionSpec(MilvusSettings settings) {
        return settings.isBm25Mode()
                ? new MilvusCollectionSpec(settings.getCollection(), settings.getAnalyzer())
                : new MilvusCollectionSpec(settings.getCollection(), settings.getDimension());
    }

    /**
     * 生成启动横幅的存放说明。
     *
     * @param settings Milvus 配置，非 null
     * @return 描述文本，非 null
     */
    private static String describe(MilvusSettings settings) {
        return "milvus " + settings.getHost() + ":" + settings.getPort() + "/" + settings.getCollection()
                + "（" + (settings.isBm25Mode() ? "bm25 全文检索" : "向量检索") + "，对话记录写入并召回）";
    }

    /**
     * 抑制 Milvus SDK 自带 gRPC 的告警噪音。
     *
     * <p>milvus-sdk-java 内部打包了 shaded gRPC，并以 {@code java.util.logging} 打日志；空闲连接保活 ping
     * 超时或关连接时会打印告警堆栈（含其 shaded 内部类的 {@code NoClassDefFoundError}），属 SDK 内部行为、
     * 不影响功能。这里把 {@code io.milvus} 日志层级抬到 {@code SEVERE}，只保留严重错误，避免控制台刷屏。
     */
    private static void quietMilvusLogging() {
        MILVUS_SDK_LOGGER.setLevel(Level.SEVERE);
    }
}
