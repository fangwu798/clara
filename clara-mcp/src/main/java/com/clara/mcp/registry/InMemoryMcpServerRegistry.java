package com.clara.mcp.registry;

import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.McpErrors;
import com.clara.mcp.api.IMcpServerRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供MCP服务端注册表的内存实现。
 */
public final class InMemoryMcpServerRegistry implements IMcpServerRegistry {
    private final Map<String, McpServerRegistration> registrations =
            new ConcurrentHashMap<>();

    public void register(McpServerRegistration registration) {
        if (registration == null) { throw new IllegalArgumentException("registration must not be null"); }
        if (registrations.putIfAbsent(key(registration.getServerKey()),
                registration) != null) {
            throw McpErrors.error(ErrorCodes.MCP_SERVER_ALREADY_REGISTERED, ErrorCategoryEnum.CONFLICT);
        }
    }
    public void updateStatus(String serverKey, McpRegistrationStatus status) {
        if (status == null) { throw new IllegalArgumentException("status must not be null"); }
        String key = key(serverKey);
        for (;;) {
            McpServerRegistration current = registrations.get(key);
            if (current == null) { throw notFound(); }
            if (registrations.replace(key, current, current.withStatus(status))) return;
        }
    }
    public List<McpServerRegistration> listEnabled() {
        List<McpServerRegistration> result = new ArrayList<>();
        for (McpServerRegistration registration : registrations.values()) {
            if (registration.isEnabled()) {
                result.add(registration);
            }
        }
        result.sort(Comparator.comparing(McpServerRegistration::getServerKey));
        return Collections.unmodifiableList(result);
    }
    public McpServerRegistration get(String serverKey) {
        return registrations.get(key(serverKey));
    }
    private static String key(String serverKey) {
        require(serverKey, "serverKey");
        return serverKey;
    }
    private static void require(String value, String field) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(field + " must not be blank"); }
    }
    private static RuntimeException notFound() {
        return McpErrors.error(ErrorCodes.MCP_SERVER_NOT_REGISTERED, ErrorCategoryEnum.CONFIGURATION);
    }
}
