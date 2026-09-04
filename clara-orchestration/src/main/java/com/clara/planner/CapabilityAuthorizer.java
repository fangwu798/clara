package com.clara.planner;

public interface CapabilityAuthorizer {
    boolean isAllowed(String capability);
}
