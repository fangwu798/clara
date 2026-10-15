package com.clara.tool;

import com.clara.core.skill.IToolCatalog;
import com.clara.dto.tool.ToolContext;
import com.clara.dto.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link CompositeToolCatalog} 的单元测试：合并顺序、同名优先级，以及对空目录 / 不可用条目的容忍。
 */
public class CompositeToolCatalogTest {

    @Test
    public void appendsSecondaryAfterPrimary() {
        IToolCatalog merged = new CompositeToolCatalog(catalog("file_ops"), catalog("search_graph"));

        assertEquals(Arrays.asList("file_ops", "search_graph"), names(merged));
    }

    @Test
    public void keepsPrimaryDeclarationWhenNamesCollide() {
        // 与 CompositeToolInvoker 的路由一致：同名工具由本地技能执行，下发给模型的也应是本地声明。
        IToolCatalog merged = new CompositeToolCatalog(catalog("file_ops"), labeled("file_ops", "外部声明"));

        ToolContext tool = merged.availableTools().get(0);

        assertEquals("file_ops", tool.getFunction().getName());
        assertEquals("本地声明", tool.getFunction().getDescription());
        assertEquals(1, merged.availableTools().size());
    }

    @Test
    public void toleratesNullCatalogsAndUnusableEntries() {
        // 目录为 null：视为无工具。
        assertTrue(new CompositeToolCatalog(null, null).availableTools().isEmpty());
        // 目录的 availableTools() 返回 null：同样按无工具处理，不抛空指针。
        assertTrue(new CompositeToolCatalog(nullReturning(), null).availableTools().isEmpty());
        // 列表里混入 null 条目、以及缺少函数签名的条目（如 web_search 这类无签名的声明）：一律跳过。
        IToolCatalog messy = entries(null, new ToolContext((ToolSpecification) null), spec("file_ops"));
        assertEquals(Arrays.asList("file_ops"), names(new CompositeToolCatalog(messy, null)));
    }

    private static List<String> names(IToolCatalog catalog) {
        List<String> names = new ArrayList<String>();
        for (ToolContext tool : catalog.availableTools()) {
            names.add(tool.getFunction().getName());
        }
        return names;
    }

    private static IToolCatalog catalog(String... names) {
        final List<String> values = Arrays.asList(names);
        return new IToolCatalog() {
            @Override
            public List<ToolContext> availableTools() {
                List<ToolContext> tools = new ArrayList<ToolContext>();
                for (String name : values) {
                    tools.add(spec(name));
                }
                return tools;
            }
        };
    }

    private static IToolCatalog labeled(final String name, final String description) {
        return new IToolCatalog() {
            @Override
            public List<ToolContext> availableTools() {
                return Collections.singletonList(new ToolContext(new ToolSpecification(name, description, schema())));
            }
        };
    }

    private static IToolCatalog entries(final ToolContext... tools) {
        return new IToolCatalog() {
            @Override
            public List<ToolContext> availableTools() {
                return Arrays.asList(tools);
            }
        };
    }

    private static IToolCatalog nullReturning() {
        return new IToolCatalog() {
            @Override
            public List<ToolContext> availableTools() {
                return null;
            }
        };
    }

    private static ToolContext spec(String name) {
        return new ToolContext(new ToolSpecification(name, "本地声明", schema()));
    }

    private static Map<String, Object> schema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        return schema;
    }
}
