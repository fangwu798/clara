package com.clara.skill.executor;

import com.clara.dto.skill.ToolMethod;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.exception.ClaraException;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SkillPermissionAuthorizer} 的单元测试：空白名单放行、白名单命中 / 拒绝与 null 处理。
 */
public class SkillPermissionAuthorizerTest {

    private static ToolMethod method(String name) {
        return new ToolMethod("id-" + name, name, ScriptTypeEnum.SHELL, "desc", null, "echo ok", null);
    }

    @Test
    public void emptyWhitelistAllowsEverything() {
        SkillPermissionAuthorizer authorizer = new SkillPermissionAuthorizer();
        assertTrue(authorizer.isAllowed(method("echo")));
        assertTrue(authorizer.isAllowed(method("calc")));
        authorizer.checkAllowed(method("calc"));
    }

    @Test
    public void whitelistAllowsListedToolOnly() {
        SkillPermissionAuthorizer authorizer = new SkillPermissionAuthorizer(Collections.singleton("echo"));
        assertTrue(authorizer.isAllowed(method("echo")));
        assertFalse(authorizer.isAllowed(method("calc")));
    }

    @Test(expected = ClaraException.class)
    public void checkAllowedDeniesUnlistedTool() {
        SkillPermissionAuthorizer authorizer = new SkillPermissionAuthorizer(Collections.singleton("echo"));
        authorizer.checkAllowed(method("calc"));
    }

    @Test
    public void nullMethodIsDenied() {
        SkillPermissionAuthorizer authorizer = new SkillPermissionAuthorizer();
        assertFalse(authorizer.isAllowed(null));
    }
}
