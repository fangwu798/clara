package com.clara.mcp.registry;

/**
 * 表示。
 */
public final class McpServerRegistration {
    private final String tenantId;
    private final String serverKey;
    private final McpTransportProfile transportProfile;
    private final String address;
    private final String authRef;
    private final String approvedIdentityName;
    private final String approvedIdentityVersion;
    private final McpRegistrationStatus status;

    public McpServerRegistration(String tenantId, String serverKey,
            McpTransportProfile transportProfile, String address, String authRef,
            String approvedIdentityName, String approvedIdentityVersion,
            McpRegistrationStatus status) {
        if (isBlank(tenantId)) { throw new IllegalArgumentException("tenantId must not be blank"); }
        if (isBlank(serverKey) || !serverKey.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("serverKey is invalid");
        }
        if (transportProfile == null) { throw new IllegalArgumentException("transportProfile must not be null"); }
        if (isBlank(address)) { throw new IllegalArgumentException("address must not be blank"); }
        if (status == null) { throw new IllegalArgumentException("status must not be null"); }
        this.tenantId = tenantId; this.serverKey = serverKey;
        this.transportProfile = transportProfile; this.address = address; this.authRef = authRef;
        this.approvedIdentityName = approvedIdentityName;
        this.approvedIdentityVersion = approvedIdentityVersion; this.status = status;
    }
    public String getTenantId() { return tenantId; }
    public String getServerKey() { return serverKey; }
    public McpTransportProfile getTransportProfile() { return transportProfile; }
    public String getAddress() { return address; }
    public String getAuthRef() { return authRef; }
    public String getApprovedIdentityName() { return approvedIdentityName; }
    public String getApprovedIdentityVersion() { return approvedIdentityVersion; }
    public McpRegistrationStatus getStatus() { return status; }
    public boolean isEnabled() { return status == McpRegistrationStatus.ACTIVE; }
    public McpServerRegistration withStatus(McpRegistrationStatus newStatus) {
        return new McpServerRegistration(tenantId, serverKey, transportProfile, address, authRef,
                approvedIdentityName, approvedIdentityVersion, newStatus);
    }
    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
