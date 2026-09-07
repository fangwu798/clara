package com.clara.model.configuration;

import com.clara.model.api.ModelProvider;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
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
    private static final long DEFAULT_MAX_FILE_BYTES = 1024L * 1024L;
    private static final Set<String> ROOT_FIELDS = fields(
            "provider", "credential", "transport", "capabilities");
    private static final Set<String> CREDENTIAL_FIELDS = fields(
            "secretRef", "environmentVariable", "apiKey");
    private static final Set<String> TRANSPORT_FIELDS = fields(
            "allowedOrigins", "connectTimeoutMillis", "readTimeoutMillis",
            "maxRequestBytes", "maxResponseBytes", "allowHttp");
    private static final Set<String> CAPABILITY_FIELDS = fields(
            "endpoint", "taskQueryEndpoint", "modelId", "apiProfile", "parameters");

    private final long maxFileBytes;
    private final ObjectReader reader;

    public ProviderYamlConfigurationLoader() {
        this(DEFAULT_MAX_FILE_BYTES);
    }

    public ProviderYamlConfigurationLoader(long maxFileBytes) {
        if (maxFileBytes <= 0L) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
        YAMLFactory factory = new YAMLFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.reader = new ObjectMapper(factory).readerFor(JsonNode.class);
        this.maxFileBytes = maxFileBytes;
    }

    public ProviderConfiguration load(Path file) {
        validateFile(file);
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > maxFileBytes) {
                throw invalid("configuration file exceeds size limit", null);
            }
            JsonNode root = readSingleDocument(bytes);
            if (root == null || !root.isObject()) {
                throw invalid("configuration root must be an object", null);
            }
            rejectUnknown(root, ROOT_FIELDS, "root");
            ModelProvider provider = provider(requiredText(root, "provider"));
            ProviderCredentialConfiguration credential = credential(requiredObject(root, "credential"));
            try {
                ProviderTransportConfiguration transport = transport(requiredObject(root, "transport"));
                Map<String, ProviderCapabilityConfiguration> capabilities =
                        capabilities(requiredObject(root, "capabilities"));
                return new ProviderConfiguration(provider, credential, transport, capabilities);
            } catch (RuntimeException exception) {
                credential.close();
                throw exception;
            }
        } catch (IOException exception) {
            throw invalid("cannot read provider YAML configuration", exception);
        }
    }

    private JsonNode readSingleDocument(byte[] bytes) throws IOException {
        MappingIterator<JsonNode> documents = reader.readValues(bytes);
        try {
            JsonNode root = documents.hasNextValue() ? documents.nextValue() : null;
            if (documents.hasNextValue()) {
                throw invalid("configuration must contain exactly one YAML document", null);
            }
            return root;
        } finally {
            documents.close();
        }
    }

    private ProviderCredentialConfiguration credential(JsonNode node) {
        rejectUnknown(node, CREDENTIAL_FIELDS, "credential");
        String apiKey = optionalText(node, "apiKey");
        return new ProviderCredentialConfiguration(requiredText(node, "secretRef"),
                optionalText(node, "environmentVariable"),
                apiKey == null ? null : apiKey.toCharArray());
    }

    private ProviderTransportConfiguration transport(JsonNode node) {
        rejectUnknown(node, TRANSPORT_FIELDS, "transport");
        JsonNode origins = node.get("allowedOrigins");
        if (origins == null || !origins.isArray() || origins.size() == 0) {
            throw invalid("transport.allowedOrigins must be a non-empty array", null);
        }
        Set<String> allowedOrigins = new LinkedHashSet<String>();
        for (JsonNode origin : origins) {
            if (!origin.isTextual() || origin.textValue().trim().isEmpty()) {
                throw invalid("transport.allowedOrigins must contain strings", null);
            }
            allowedOrigins.add(origin.textValue());
        }
        return new ProviderTransportConfiguration(allowedOrigins,
                requiredInt(node, "connectTimeoutMillis"),
                requiredInt(node, "readTimeoutMillis"),
                requiredInt(node, "maxRequestBytes"),
                requiredInt(node, "maxResponseBytes"),
                optionalBoolean(node, "allowHttp", false));
    }

    private Map<String, ProviderCapabilityConfiguration> capabilities(JsonNode node) {
        if (node.size() == 0) {
            throw invalid("capabilities must not be empty", null);
        }
        Map<String, ProviderCapabilityConfiguration> result =
                new LinkedHashMap<String, ProviderCapabilityConfiguration>();
        Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (entry.getKey().trim().isEmpty() || !entry.getValue().isObject()) {
                throw invalid("capability must have a name and object value", null);
            }
            JsonNode value = entry.getValue();
            rejectUnknown(value, CAPABILITY_FIELDS, "capability " + entry.getKey());
            result.put(entry.getKey(), new ProviderCapabilityConfiguration(
                    uri(requiredText(value, "endpoint"), "endpoint"),
                    optionalUri(value, "taskQueryEndpoint"),
                    optionalText(value, "modelId"), optionalText(value, "apiProfile"),
                    parameters(value.get("parameters"))));
        }
        return result;
    }

    private Map<String, Object> parameters(JsonNode node) {
        if (node == null || node.isNull()) {
            return Collections.emptyMap();
        }
        if (!node.isObject()) {
            throw invalid("capability parameters must be an object", null);
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
                throw invalid("capability parameters must contain scalar values", null);
            }
        }
        return result;
    }

    private void validateFile(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("configuration file must not be null");
        }
        String name = file.getFileName() == null
                ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
            throw invalid("configuration file must use .yml or .yaml", null);
        }
        try {
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("configuration path must be a regular non-symbolic file", null);
            }
            if (Files.size(file) > maxFileBytes) {
                throw invalid("configuration file exceeds size limit", null);
            }
        } catch (IOException exception) {
            throw invalid("cannot inspect provider YAML configuration", exception);
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw invalid(field + " must be an object", null);
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String field) {
        String value = optionalText(parent, field);
        if (value == null) {
            throw invalid(field + " must be a non-blank string", null);
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().trim().isEmpty()) {
            throw invalid(field + " must be a non-blank string", null);
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be an integer", null);
        }
        return value.intValue();
    }

    private static boolean optionalBoolean(JsonNode parent, String field, boolean fallback) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw invalid(field + " must be a boolean", null);
        }
        return value.booleanValue();
    }

    private static URI optionalUri(JsonNode parent, String field) {
        String value = optionalText(parent, field);
        return value == null ? null : uri(value, field);
    }

    private static URI uri(String value, String field) {
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw invalid(field + " must be a safe absolute HTTP URI", null);
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw invalid(field + " is not a valid URI", exception);
        }
    }

    private static ModelProvider provider(String value) {
        try {
            return ModelProvider.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("provider is not supported", exception);
        }
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String section) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalid("unknown field in " + section + ": " + name, null);
            }
        }
    }

    private static Set<String> fields(String... names) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(Arrays.asList(names)));
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return cause == null ? new IllegalArgumentException(message)
                : new IllegalArgumentException(message, cause);
    }
}
