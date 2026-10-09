package com.clara.mcp.registry;

import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供MCP服务端注册表的内存实现。
 */
public final class InMemoryMcpServerRegistry implements McpServerRegistry {
    private final Map<String, McpServerRegistration> registrations =
            new ConcurrentHashMap<String, McpServerRegistration>();

    public void register(McpServerRegistration registration) {
        if (registration == null) { throw new IllegalArgumentException("registration must not be null"); }
        if (registrations.putIfAbsent(key(registration.getTenantId(), registration.getServerKey()),
                registration) != null) {
            throw McpErrors.error(ErrorCodes.MCP_SERVER_ALREADY_REGISTERED, ErrorCategory.CONFLICT);
        }
    }
    public void updateStatus(String tenantId, String serverKey, McpRegistrationStatus status) {
        if (status == null) { throw new IllegalArgumentException("status must not be null"); }
        String key = key(tenantId, serverKey);
        for (;;) {
            McpServerRegistration current = registrations.get(key);
            if (current == null) { throw notFound(); }
            if (registrations.replace(key, current, current.withStatus(status))) return;
        }
    }
    public List<McpServerRegistration> listEnabled(String tenantId) {
        require(tenantId, "tenantId");
        List<McpServerRegistration> result = new ArrayList<McpServerRegistration>();
        for (McpServerRegistration registration : registrations.values()) {
            if (tenantId.equals(registration.getTenantId()) && registration.isEnabled()) {
                result.add(registration);
            }
        }
        Collections.sort(result, new Comparator<McpServerRegistration>() {
            public int compare(McpServerRegistration left, McpServerRegistration right) {
                return left.getServerKey().compareTo(right.getServerKey());
            }
        });
        return Collections.unmodifiableList(result);
    }
    public McpServerRegistration get(String tenantId, String serverKey) {
        return registrations.get(key(tenantId, serverKey));
    }
    private static String key(String tenantId, String serverKey) {
        require(tenantId, "tenantId"); require(serverKey, "serverKey");
        return tenantId + '\u0000' + serverKey;
    }
    private static void require(String value, String field) {
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException(field + " must not be blank"); }
    }
    private static RuntimeException notFound() {
        return McpErrors.error(ErrorCodes.MCP_SERVER_NOT_REGISTERED, ErrorCategory.CONFIGURATION);
    }
}
