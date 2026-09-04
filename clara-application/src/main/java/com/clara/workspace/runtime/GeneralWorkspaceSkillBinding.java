package com.clara.workspace.runtime;

import com.clara.skill.api.SkillExecutionType;
import com.clara.skill.builtin.GeneralWorkspaceSkill;
import com.clara.skill.runtime.RoutingSkillHandler;
import com.clara.skill.runtime.SkillHandler;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class GeneralWorkspaceSkillBinding {
    private GeneralWorkspaceSkillBinding() {
    }

    public static Map<SkillExecutionType, SkillHandler> handlers(GeneralWorkspaceSkillHandler handler) {
        if (handler == null) throw new IllegalArgumentException("handler is required");
        Map<String, SkillHandler> routes = Collections.<String, SkillHandler>singletonMap(
                GeneralWorkspaceSkill.HANDLER_REF, handler);
        EnumMap<SkillExecutionType, SkillHandler> result = new EnumMap<SkillExecutionType, SkillHandler>(SkillExecutionType.class);
        result.put(SkillExecutionType.JAVA_HANDLER, new RoutingSkillHandler(routes));
        return Collections.unmodifiableMap(result);
    }
}
