package com.clara.skill;

import com.clara.enums.ErrorCategoryEnum;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.exception.ClaraException;
import com.clara.skill.registry.SkillRegistryErrors;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Error factory coverage: every factory must map to the documented code, category and retry flag.
 */
public class SkillErrorsTest {

    @Test
    public void skillErrorFactoriesCarryDomainSemantics() {
        ClaraException notFound = SkillErrors.skillNotFound("demo");
        assertTrue(notFound.getMessage().contains("demo"));
        assertEquals(ErrorCategoryEnum.VALIDATION, notFound.getError().getCategory());
        assertFalse(notFound.getError().isRetryable());

        assertEquals(ErrorCategoryEnum.AUTHORIZATION,
                SkillErrors.permissionDenied("demo").getError().getCategory());
        assertEquals(ErrorCategoryEnum.VALIDATION,
                SkillErrors.schemaInvalid("bad").getError().getCategory());

        ClaraException timeout = SkillErrors.timeout("demo");
        assertEquals(ErrorCategoryEnum.TIMEOUT, timeout.getError().getCategory());
        assertTrue(timeout.getError().isRetryable());

        ClaraException handler = SkillErrors.handlerNotFound(null);
        assertEquals(ErrorCategoryEnum.CAPABILITY, handler.getError().getCategory());
        assertTrue(handler.getMessage().contains("null"));

        ClaraException handlerWithType = SkillErrors.handlerNotFound(ScriptTypeEnum.PYTHON);
        assertTrue(handlerWithType.getMessage().contains("PYTHON"));

        assertEquals(ErrorCategoryEnum.EXTERNAL_SERVICE,
                SkillErrors.executionFailed("boom").getError().getCategory());
        assertEquals(ErrorCategoryEnum.VALIDATION,
                SkillErrors.invalidArgument("bad").getError().getCategory());
        assertEquals(ErrorCategoryEnum.CANCELLED,
                SkillErrors.cancelled().getError().getCategory());
    }

    @Test
    public void registryErrorFactoriesCarryDomainSemantics() {
        ClaraException invalid = SkillRegistryErrors.invalidFile("a.json", null);
        assertTrue(invalid.getMessage().contains("a.json"));
        assertEquals(ErrorCategoryEnum.VALIDATION, invalid.getError().getCategory());

        ClaraException invalidWithReason = SkillRegistryErrors.invalidFile("a.json", "  ");
        assertTrue(invalidWithReason.getMessage().contains("a.json"));

        ClaraException invalidDetailed = SkillRegistryErrors.invalidFile("a.json", "broken");
        assertTrue(invalidDetailed.getMessage().contains("broken"));

        assertTrue(SkillRegistryErrors.unsupportedType(null).getMessage().contains("null"));
        assertTrue(SkillRegistryErrors.unsupportedType("GROOVY").getMessage().contains("GROOVY"));

        ClaraException duplicate = SkillRegistryErrors.duplicateId("id-1");
        assertEquals(ErrorCategoryEnum.CONFLICT, duplicate.getError().getCategory());
        assertFalse(duplicate.getError().isRetryable());

        ClaraException io = SkillRegistryErrors.ioError("b.json");
        assertEquals(ErrorCategoryEnum.EXTERNAL_SERVICE, io.getError().getCategory());
        assertNotNull(io.getMessage());
    }
}