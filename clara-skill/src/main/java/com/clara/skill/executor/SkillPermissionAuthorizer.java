package com.clara.skill.executor;

import com.clara.dto.skill.ToolMethod;
import com.clara.exception.ClaraException;
import com.clara.skill.SkillErrors;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 技能执行权限校验器。
 *
 * <p>维护工具名白名单：白名单为空表示放行全部；非空时仅允许白名单内的工具执行。
 * 目录、网络等更细粒度的约束可在后续实现中扩展。
 */
public final class SkillPermissionAuthorizer {

    private final Set<String> allowedToolNames;

    /**
     * 创建放行全部工具的校验器。
     */
    public SkillPermissionAuthorizer() {
        this(Collections.<String>emptySet());
    }

    /**
     * 按工具名白名单创建校验器。
     *
     * @param allowedToolNames 允许执行的工具名集合，可为 null 或空（表示放行全部）
     */
    public SkillPermissionAuthorizer(Collection<String> allowedToolNames) {
        Set<String> copy = new HashSet<>();
        if (allowedToolNames != null) {
            for (String name : allowedToolNames) {
                if (name != null && !name.trim().isEmpty()) {
                    copy.add(name);
                }
            }
        }
        this.allowedToolNames = Collections.unmodifiableSet(copy);
    }

    /**
     * 判断工具方法是否被允许执行。
     *
     * @param method 工具方法，可为 null
     * @return 白名单为空或包含该方法名时返回 true
     */
    public boolean isAllowed(ToolMethod method) {
        return method != null && (allowedToolNames.isEmpty() || allowedToolNames.contains(method.getName()));
    }

    /**
     * 校验工具方法是否被允许执行，不允许时抛出权限异常。
     *
     * @param method 工具方法，不可为 null
     * @throws ClaraException 权限被拒时
     */
    public void checkAllowed(ToolMethod method) {
        if (!isAllowed(method)) {
            throw SkillErrors.permissionDenied(method == null ? "null" : method.getName());
        }
    }
}
