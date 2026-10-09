package com.clara.core.planner;

/**
 * 能力授权接口：校验对能力的访问权限。
 */
public interface ICapabilityAuthorizer {

    /**
     * 判断是否允许访问指定能力。
     *
     * @param capability 能力标识，不可为 null
     * @return 允许时返回 {@code true}
     */
    boolean isAllowed(String capability);
}
