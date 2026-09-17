package com.clara.model.configuration;

import com.clara.model.api.ModelProvider;
import com.clara.model.utils.JsonNodeUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从单个平台 YAML 文件加载并严格校验模型配置。
 *
 * <p>常规用法：为每个平台准备一个独立的 {@code .yml} 或 {@code .yaml} 文件，调用 {@link #load}
 * 得到 {@link ProviderConfiguration}，随后创建传输、凭据解析器和具体平台配置。加载只发生在调用时，
 * 不会扫描工作目录或合并多个平台文件。
 *
 * <p>注意：文件必须是普通文件且不能是符号链接，默认最大 1 MiB。未知字段、重复 YAML 键、多文档内容、
 * 非标量扩展参数以及不安全 URI 都会被拒绝。错误消息不会包含密钥值，但内联密钥仍只应放在被 Git 忽略的
 * 本地目录；可提交样例应使用环境变量名。
 */
public final class ProviderYamlConfigurationLoader {
    /**
     * 默认配置文件大小上限，即 1 MiB。
     */
    private static final long DEFAULT_MAX_FILE_BYTES = 1024L * 1024L;
    /**
     * 根节点允许出现的字段集合。
     */
    private static final Set<String> ROOT_FIELDS = JsonNodeUtils.fieldSet("provider", "credential", "transport", "capabilities");
    /**
     * credential 节点允许出现的字段集合。
     */
    private static final Set<String> CREDENTIAL_FIELDS = JsonNodeUtils.fieldSet("secretRef", "environmentVariable", "apiKey");
    /**
     * transport 节点允许出现的字段集合。
     */
    private static final Set<String> TRANSPORT_FIELDS = JsonNodeUtils.fieldSet("allowedOrigins", "connectTimeoutMillis", "readTimeoutMillis", "maxRequestBytes", "maxResponseBytes", "allowHttp");
    /**
     * capability 节点允许出现的字段集合。
     */
    private static final Set<String> CAPABILITY_FIELDS = JsonNodeUtils.fieldSet("endpoint", "taskQueryEndpoint", "modelId", "apiProfile", "parameters");

    /**
     * 当前加载器允许的文件大小上限，单位字节。
     */
    private final long maxFileBytes;
    /**
     * 启用了严格重复键检测的 YAML 读取器。
     */
    private final ObjectReader reader;

    /**
     * 使用默认的 1 MiB 文件大小上限创建加载器。
     */
    public ProviderYamlConfigurationLoader() {
        this(DEFAULT_MAX_FILE_BYTES);
    }

    /**
     * 使用指定的文件大小上限创建加载器。
     *
     * @param maxFileBytes 文件大小上限，单位字节，必须为正数
     * @throws IllegalArgumentException maxFileBytes 非正时
     */
    public ProviderYamlConfigurationLoader(long maxFileBytes) {
        if (maxFileBytes <= 0L) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
        YAMLFactory factory = new YAMLFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.reader = new ObjectMapper(factory).readerFor(JsonNode.class);
        this.maxFileBytes = maxFileBytes;
    }

    /**
     * 加载并严格校验单个平台 YAML 文件。
     *
     * @param file 配置文件路径
     * @return 解析得到的平台配置
     * @throws IllegalArgumentException 文件不合规或内容非法时
     */
    public ProviderConfiguration load(Path file) {
        validateFile(file);
        try {
            byte[] bytes = Files.readAllBytes(file);
            JsonNode root = readSingleDocument(bytes);
            if (root == null || !root.isObject()) {
                throw JsonNodeUtils.invalidConfig("configuration root must be an object", null);
            }
            JsonNodeUtils.rejectUnknown(root, ROOT_FIELDS, "root");
            ModelProvider provider = provider(JsonNodeUtils.requiredText(root, "provider"));
            ProviderCredentialConfiguration credential = credential(JsonNodeUtils.requiredObject(root, "credential"));
            try {
                ProviderTransportConfiguration transport = transport(JsonNodeUtils.requiredObject(root, "transport"));
                Map<String, ProviderCapabilityConfiguration> capabilities = capabilities(JsonNodeUtils.requiredObject(root, "capabilities"));

                return new ProviderConfiguration(provider, credential, transport, capabilities);
            } catch (RuntimeException exception) {
                credential.close();
                throw exception;
            }
        } catch (IOException exception) {
            throw JsonNodeUtils.invalidConfig("cannot read provider YAML configuration", exception);
        }
    }

    /**
     * 读取唯一一个 YAML 文档。
     *
     * @param bytes 文件字节内容
     * @return 根节点，空文档时为 null
     * @throws IOException              读取失败时
     * @throws IllegalArgumentException 存在多个文档时
     */
    private JsonNode readSingleDocument(byte[] bytes) throws IOException {
        MappingIterator<JsonNode> documents = reader.readValues(bytes);
        try {
            JsonNode root = documents.hasNextValue() ? documents.nextValue() : null;
            if (documents.hasNextValue()) {
                throw JsonNodeUtils.invalidConfig("configuration must contain exactly one YAML document", null);
            }
            return root;
        } finally {
            documents.close();
        }
    }

    /**
     * 解析 credential 节点。
     *
     * @param node credential 节点
     * @return 凭据配置
     */
    private ProviderCredentialConfiguration credential(JsonNode node) {
        JsonNodeUtils.rejectUnknown(node, CREDENTIAL_FIELDS, "credential");
        String apiKey = JsonNodeUtils.optionalText(node, "apiKey");
        return new ProviderCredentialConfiguration(
                JsonNodeUtils.requiredText(node, "secretRef"),
                JsonNodeUtils.optionalText(node, "environmentVariable"),
                apiKey == null ? null : apiKey.toCharArray()
        );
    }

    /**
     * 解析 transport 节点。
     *
     * @param node transport 节点
     * @return 传输配置
     */
    private ProviderTransportConfiguration transport(JsonNode node) {
        JsonNodeUtils.rejectUnknown(node, TRANSPORT_FIELDS, "transport");
        JsonNode origins = node.get("allowedOrigins");
        if (origins == null || !origins.isArray() || origins.size() == 0) {
            throw JsonNodeUtils.invalidConfig("transport.allowedOrigins must be a non-empty array", null);
        }
        Set<String> allowedOrigins = new LinkedHashSet<String>();
        for (JsonNode origin : origins) {
            if (!origin.isTextual() || origin.textValue().trim().isEmpty()) {
                throw JsonNodeUtils.invalidConfig("transport.allowedOrigins must contain strings", null);
            }
            allowedOrigins.add(origin.textValue());
        }
        return new ProviderTransportConfiguration(allowedOrigins,
                JsonNodeUtils.requiredInt(node, "connectTimeoutMillis"),
                JsonNodeUtils.requiredInt(node, "readTimeoutMillis"),
                JsonNodeUtils.requiredInt(node, "maxRequestBytes"),
                JsonNodeUtils.requiredInt(node, "maxResponseBytes"),
                JsonNodeUtils.optionalBoolean(node, "allowHttp", false));
    }

    /**
     * 解析 capabilities 节点。
     *
     * @param node capabilities 节点
     * @return 能力名称到能力配置的映射
     */
    private Map<String, ProviderCapabilityConfiguration> capabilities(JsonNode node) {
        if (node.size() == 0) {
            throw JsonNodeUtils.invalidConfig("capabilities must not be empty", null);
        }
        Map<String, ProviderCapabilityConfiguration> result =
                new LinkedHashMap<String, ProviderCapabilityConfiguration>();
        Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (entry.getKey().trim().isEmpty() || !entry.getValue().isObject()) {
                throw JsonNodeUtils.invalidConfig("capability must have a name and object value", null);
            }
            JsonNode value = entry.getValue();
            JsonNodeUtils.rejectUnknown(value, CAPABILITY_FIELDS, "capability " + entry.getKey());
            result.put(entry.getKey(), new ProviderCapabilityConfiguration(
                    JsonNodeUtils.safeHttpUri(JsonNodeUtils.requiredText(value, "endpoint"), "endpoint"),
                    JsonNodeUtils.optionalUri(value, "taskQueryEndpoint"),
                    JsonNodeUtils.optionalText(value, "modelId"),
                    JsonNodeUtils.optionalText(value, "apiProfile"),
                    parameters(value.get("parameters"))));
        }
        return result;
    }

    /**
     * 解析能力扩展参数，仅接受字符串、数字和布尔值。
     *
     * @param node parameters 节点，可为 null
     * @return 参数映射，节点为空时返回空映射
     */
    private Map<String, Object> parameters(JsonNode node) {
        if (node == null || node.isNull()) {
            return Collections.emptyMap();
        }
        if (!node.isObject()) {
            throw JsonNodeUtils.invalidConfig("capability parameters must be an object", null);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            JsonNode value = entry.getValue();
            if (value.isTextual()) {
                result.put(entry.getKey(), value.textValue());
            } else if (value.isIntegralNumber()) {
                result.put(entry.getKey(), value.canConvertToInt()
                        ? Integer.valueOf(value.intValue()) : Long.valueOf(value.longValue()));
            } else if (value.isFloatingPointNumber()) {
                result.put(entry.getKey(), Double.valueOf(value.doubleValue()));
            } else if (value.isBoolean()) {
                result.put(entry.getKey(), Boolean.valueOf(value.booleanValue()));
            } else {
                throw JsonNodeUtils.invalidConfig("capability parameters must contain scalar values", null);
            }
        }
        return result;
    }

    /**
     * 校验配置路径为普通 .yml/.yaml 文件且不超过大小上限。
     *
     * @param file 配置文件路径
     * @throws IllegalArgumentException 路径为空、后缀不符、为符号链接或超过大小上限时
     */
    private void validateFile(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("configuration file must not be null");
        }
        String name = file.getFileName() == null ? ""
                : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
            throw JsonNodeUtils.invalidConfig("configuration file must use .yml or .yaml", null);
        }
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw JsonNodeUtils.invalidConfig("configuration path must be a regular non-symbolic file", null);
            }
            if (Files.size(file) > maxFileBytes) {
                throw JsonNodeUtils.invalidConfig("configuration file exceeds size limit", null);
            }
        } catch (IOException exception) {
            throw JsonNodeUtils.invalidConfig("cannot inspect provider YAML configuration", exception);
        }
    }

    /**
     * 将平台名称解析为 ModelProvider 枚举。
     *
     * @param value 平台名称，不区分大小写
     * @return 对应的模型平台
     * @throws IllegalArgumentException 平台不受支持时
     */
    private static ModelProvider provider(String value) {
        try {
            return ModelProvider.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw JsonNodeUtils.invalidConfig("provider is not supported", exception);
        }
    }
}
