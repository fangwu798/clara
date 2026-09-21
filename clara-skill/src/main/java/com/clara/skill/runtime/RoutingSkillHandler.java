//package com.clara.skill.runtime;
//
//import com.clara.kernel.exception.ErrorCategory;
//import com.clara.kernel.exception.ErrorCodes;
//import com.clara.skill.api.SkillDefinition;
//import com.clara.skill.api.SkillExecutionRequest;
//import com.clara.skill.api.SkillExecutionResult;
//import com.clara.skill.api.SkillResumeRequest;
//import java.util.Collections;
//import java.util.LinkedHashMap;
//import java.util.Map;
//
///**
// * 处理路由技能操作。
// */
//public final class RoutingSkillHandler implements SkillHandler {
//    private final Map<String, SkillHandler> handlers;
//
//    public RoutingSkillHandler(Map<String, SkillHandler> handlers) {
//        if (handlers == null || handlers.isEmpty()) { throw new IllegalArgumentException("handlers are required"); }
//        LinkedHashMap<String, SkillHandler> copy = new LinkedHashMap<String, SkillHandler>();
//        for (Map.Entry<String, SkillHandler> entry : handlers.entrySet()) {
//            if (entry.getKey() == null || entry.getKey().trim().isEmpty() || entry.getValue() == null) {
//                throw new IllegalArgumentException("handler route is invalid");
//            }
//            copy.put(entry.getKey(), entry.getValue());
//        }
//        this.handlers = Collections.unmodifiableMap(copy);
//    }
//
//    public SkillExecutionResult execute(SkillDefinition definition, SkillExecutionRequest request) throws Exception {
//        return handler(definition).execute(definition, request);
//    }
//
//    public SkillExecutionResult resume(SkillDefinition definition, SkillResumeRequest request) throws Exception {
//        return handler(definition).resume(definition, request);
//    }
//
//    private SkillHandler handler(SkillDefinition definition) {
//        SkillHandler result = handlers.get(definition.getExecutionRef());
//        if (result == null) { throw SkillErrors.error(ErrorCodes.SKILL_HANDLER_NOT_FOUND, ErrorCategory.CONFIGURATION); }
//        return result;
//    }
//}
