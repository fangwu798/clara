package com.clara.model.configuration;

import com.clara.kernel.config.ProviderConfiguration;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 把平台 YAML 文件严格绑定到配置类：注解声明形状，本类只负责「文件与解析」的公共部分。
 *
 * <p>是什么、在哪一层：本类属于 {@code clara-model} 的配置解析层（{@code com.clara.model.configuration}），
 * 是实现层组件，是配置加载的唯一实现路径——{@link ProviderYamlConfigurationLoader} 是它的门面，
 * 不再有第二套手工逐字段解析。
 *
 * <p>谁创建谁调用：由 {@code ProviderYamlConfigurationLoader} 在构造时创建并持有；业务代码通常只调用
 * loader 的 {@code load(Path)}，不直接使用本类。
 *
 * <p>使用方法：
 * <ol>
 *   <li>装配期构造一次（默认上限 1 MiB，或传入自定义上限）并长期复用；</li>
 *   <li>调用 {@link #bind(Path, Class)} 得到配置对象，类型参数的类需用 {@code @JsonCreator} +
 *       {@code @JsonProperty} 声明自己的 YAML 形状；</li>
 *   <li>绑定成功后再由调用方负责对象生命周期（如 {@link ProviderConfiguration#close()}）。</li>
 * </ol>
 *
 * <p>本类承担的职责：
 * <ul>
 *   <li>文件校验：必须是普通 {@code .yml} / {@code .yaml} 文件、不得是符号链接、不得超过大小上限；</li>
 *   <li>严格读取：开启重复键检测、拒绝未知配置项、只允许一个 YAML 文档、枚举取值大小写不敏感；</li>
 *   <li>错误中文化：把 Jackson 的异常翻译成带字段路径与行号的中文提示，风格与错误码体系一致。</li>
 * </ul>
 *
 * <p>生命周期与线程安全：持有的是 {@link ObjectMapper}，其配置在构造后不再变化，可跨线程并发绑定不同
 * 文件；无 {@code close()} 动作。
 *
 * <p>失败语义：所有失败都抛 {@link IllegalArgumentException}（原因链保留原始 Jackson 异常），消息为中文：
 * 文件不合规、未知配置项、类型不符或缺失、构造期校验失败、解析失败分别有对应文案。配置错误不可重试，
 * <b>不会</b>退回默认值继续运行。
 *
 * @see ProviderYamlConfigurationLoader
 */
public final class StrictYamlBinder {

    /**
     * 默认配置文件大小上限，即 1 MiB。
     */
    private static final long DEFAULT_MAX_FILE_BYTES = 1024L * 1024L;

    /**
     * 当前允许的文件大小上限，单位字节。
     */
    private final long maxFileBytes;
    /**
     * 启用了严格选项的 YAML 映射器，构造后不再修改配置。
     */
    private final ObjectMapper mapper;

    /**
     * 使用默认的 1 MiB 文件大小上限创建绑定器。
     */
    public StrictYamlBinder() {
        this(DEFAULT_MAX_FILE_BYTES);
    }

    /**
     * 使用指定的文件大小上限创建绑定器。
     *
     * @param maxFileBytes 文件大小上限，单位字节，必须为正数
     * @throws IllegalArgumentException maxFileBytes 非正时
     */
    public StrictYamlBinder(long maxFileBytes) {
        if (maxFileBytes <= 0L) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
        YAMLFactory factory = YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        ObjectMapper configured = JsonMapper.builder(factory)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        this.mapper = configured;
        this.maxFileBytes = maxFileBytes;
    }

    /**
     * 读取单个 YAML 文件并严格绑定到指定类型。
     *
     * @param file YAML 文件路径，非 null
     * @param type 目标配置类型（需用 {@code @JsonCreator} 声明形状），非 null
     * @param <T>  配置类型
     * @return 绑定得到的配置对象，非 null
     * @throws IllegalArgumentException 文件不合规、读取失败、存在未知配置项、类型不符或缺失、构造期校验
     *                                  失败、内容为空或无法解析时
     */
    public <T> T bind(Path file, Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("configuration type must not be null");
        }
        validateFile(file);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取配置文件：" + describe(file), exception);
        }
        try {
            T bound = mapper.readValue(bytes, type);
            if (bound == null) {
                throw new IllegalArgumentException("配置文件内容为空：" + describe(file));
            }
            return bound;
        } catch (UnrecognizedPropertyException exception) {
            throw new IllegalArgumentException("未知配置项：" + path(exception) + line(exception)
                    + "，请核对键名是否与配置类字段一致（不支持旧键名）", exception);
        } catch (ValueInstantiationException exception) {
            throw new IllegalArgumentException("配置项 " + path(exception) + " 无法构造"
                    + line(exception) + "：" + rootMessage(exception), exception);
        } catch (MismatchedInputException exception) {
            throw new IllegalArgumentException("配置项 " + path(exception) + " 类型不符或缺失"
                    + line(exception) + "：" + rootMessage(exception), exception);
        } catch (JsonMappingException exception) {
            throw new IllegalArgumentException("配置文件绑定失败" + line(exception) + "："
                    + rootMessage(exception), exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("配置文件解析失败：" + describe(file), exception);
        }
    }

    /**
     * 校验配置路径为普通 .yml/.yaml 文件且不超过大小上限。
     *
     * @param file 配置文件路径
     * @throws IllegalArgumentException 路径为空、后缀不符、为符号链接、不是普通文件或超过大小上限时
     */
    private void validateFile(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("配置文件路径不能为空");
        }
        Path fileName = file.getFileName();
        String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
            throw new IllegalArgumentException("配置文件必须使用 .yml 或 .yaml 后缀：" + describe(file));
        }
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("配置路径必须是普通文件且不能是符号链接：" + describe(file));
            }
            if (Files.size(file) > maxFileBytes) {
                throw new IllegalArgumentException("配置文件超过大小上限（上限 " + maxFileBytes
                        + " 字节）：" + describe(file));
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法检查配置文件：" + describe(file), exception);
        }
    }

    /**
     * 拼出异常发生处的字段路径。
     *
     * @param exception 映射异常
     * @return 形如 {@code transport.headers} 的路径；无路径信息时为 {@code <root>}
     */
    private static String path(JsonMappingException exception) {
        StringBuilder builder = new StringBuilder();
        for (JsonMappingException.Reference reference : exception.getPath()) {
            if (reference.getFieldName() != null) {
                if (builder.length() > 0) {
                    builder.append('.');
                }
                builder.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                builder.append('[').append(reference.getIndex()).append(']');
            }
        }
        return builder.length() == 0 ? "<root>" : builder.toString();
    }

    /**
     * 拼出异常发生处的行号片段。
     *
     * @param exception 映射异常
     * @return 形如「（第 12 行）」的片段；无位置信息时为空串
     */
    private static String line(JsonMappingException exception) {
        if (exception.getLocation() == null || exception.getLocation().getLineNr() <= 0) {
            return "";
        }
        return "（第 " + exception.getLocation().getLineNr() + " 行）";
    }

    /**
     * 取出最贴近原因的异常消息。
     *
     * <p>{@link ValueInstantiationException} 会把配置类构造器抛出的校验消息包在 cause 中，这里取最内层
     * 消息，避免把 Jackson 的包装文案暴露给使用者。
     *
     * @param exception 映射异常
     * @return 最内层原因的消息；为 null 时退化为异常自身的消息
     */
    private static String rootMessage(JsonMappingException exception) {
        Throwable cause = exception.getCause();
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause == null ? exception.getOriginalMessage() : cause.getMessage();
        return message == null ? exception.getOriginalMessage() : message;
    }

    /**
     * 生成用于错误提示的文件描述。
     *
     * @param file 配置文件路径，可为 null
     * @return 文件名（含父目录名）文本
     */
    private static String describe(Path file) {
        return file == null ? "<null>" : file.toString();
    }
}
