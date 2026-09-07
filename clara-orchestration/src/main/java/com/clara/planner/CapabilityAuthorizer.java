package com.clara.planner;

/**
 * 校验对能力的访问权限。
 */
public interface CapabilityAuthorizer {
    boolean isAllowed(String capability);
}
