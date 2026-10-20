package com.clara.workflow;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link WorkflowCatalog} 的单元测试：清单来自仓库自带的 config/workflow（含 custom 目录），
 * 验证「标识 → 描述」、自定义工作流摘要与 run_workflow 工具描述装饰。
 */
public class WorkflowCatalogTest {

    @Test
    public void descriptionsContainBuiltinWorkflowsSortedById() {
        Map<String, String> descriptions = WorkflowCatalog.descriptions();

        assertFalse(descriptions.isEmpty());
        assertTrue(descriptions.containsKey("analyze_repo_docs"));
        assertTrue(descriptions.containsKey("decision_panel"));
        String previous = null;
        for (String id : descriptions.keySet()) {
            if (previous != null) {
                assertTrue("应按标识升序：" + previous + " -> " + id, previous.compareTo(id) < 0);
            }
            previous = id;
        }
    }

    @Test
    public void describeMatchesWorkflowIdAndRejectsBlankOrUnknown() {
        assertEquals("多角色多轮自由讨论并形成共同决议（必填 topic）", WorkflowCatalog.describe("decision_panel"));
        assertNull(WorkflowCatalog.describe(null));
        assertNull(WorkflowCatalog.describe("  "));
        assertNull(WorkflowCatalog.describe("no-such-workflow"));
    }

    @Test
    public void describeTrimsSurroundingWhitespace() {
        assertEquals(WorkflowCatalog.describe("decision_panel"), WorkflowCatalog.describe(" decision_panel "));
    }

    @Test
    public void customSummariesOnlyListCustomDirectoryEntries() {
        List<String> summaries = WorkflowCatalog.customSummaries();

        assertEquals(1, summaries.size());
        String line = summaries.get(0);
        assertTrue(line, line.startsWith("decision_panel（"));
        assertTrue(line, line.contains("必填入参：topic"));
    }

    @Test
    public void catalogTextGroupsBuiltinAndCustom() {
        String catalog = WorkflowCatalog.catalogText();

        assertTrue(catalog.startsWith("内置工作流："));
        assertTrue(catalog.contains("analyze_repo_docs"));
        assertTrue(catalog.contains("；自定义工作流（config/workflow/custom 下，写入即可用）："));
        assertTrue(catalog.contains("decision_panel"));
    }

    @Test
    public void decorateOnlyTouchesRunWorkflowTool() {
        String original = "按名执行工作流";
        String decorated = WorkflowCatalog.decorateToolDescription("run_workflow", original);

        assertTrue(decorated.startsWith(original + " 当前可用：内置工作流："));

        String other = "检索代码";
        assertSame(other, WorkflowCatalog.decorateToolDescription("text_search", other));
        assertNull(WorkflowCatalog.decorateToolDescription("text_search", null));
    }

    @Test
    public void decorateToleratesNullDescriptionForRunWorkflow() {
        String decorated = WorkflowCatalog.decorateToolDescription("run_workflow", null);

        assertNotNull(decorated);
        assertTrue(decorated.contains("当前可用："));
    }

    @Test
    public void directoryPointsToExistingWorkflowFolder() {
        assertTrue(WorkflowCatalog.directory().toFile().isDirectory());
    }
}