package com.clara.codegraph;

import com.clara.config.CodeGraphSettings;
import com.clara.core.memory.IContextTraceSink;
import com.clara.core.skill.IToolInvoker;
import com.clara.core.tool.IToolInvocation;
import com.clara.dto.tool.ToolResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link CodeGraphSupplement} 的行为测试：检索入参、命中注入、零命中 / 报错不注入、结果缓存与开关，
 * 全部用假工具调用器，不连真实图谱。
 *
 * <p>为什么单测要盯这些点：这条链路是静默的——失败一律返回空列表、对话照常进行，所以「没注入」到底是
 * 图谱没命中还是代码写错了，只能靠测试卡住。历史上踩过的两个坑都在这：漏传 regex 导致多关键词恒零命中、
 * 零命中时装进一份 1.5 KB 的空结果。
 *
 * <p>另盯一组「上报」：控制台观察靠它区分命中 / 未命中 / 调用失败，而它对对话结果没有影响。
 */
public class CodeGraphSupplementTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final String HIT = "{\"op\":\"search\",\"ok\":true,\"result\":{\"rows\":"
            + "[[\"a.B\",\"Method\",\"B.java\"]],\"total_results\":1}}";

    private static final String ZERO_HIT = "{\"op\":\"search\",\"ok\":true,\"result\":{\"rows\":[],"
            + "\"raw_matches\":{\"rows\":[]},\"total_grep_matches\":0,\"total_results\":0,"
            + "\"raw_match_count\":0}}";

    @Test
    public void hitIsInjectedWithRegexSearch() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.output = HIT;

        List<String> knowledge = provider(invoker, true).supplement("看下 ContextAssembler 怎么装配上下文");

        assertEquals(1, knowledge.size());
        assertTrue(knowledge.get(0).startsWith("【代码知识图谱补充信息】"));
        assertTrue(knowledge.get(0).contains("a.B"));
        assertEquals("code_graph", invoker.names.get(0));
        assertTrue(invoker.arguments.get(0).contains("\"op\":\"search\""));
        assertTrue(invoker.arguments.get(0).contains("\"regex\":true"));
        assertTrue(invoker.arguments.get(0).contains("ContextAssembler"));
    }

    @Test
    public void zeroHitResultIsNotInjected() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.output = ZERO_HIT;

        assertTrue(provider(invoker, true).supplement("看下 ContextAssembler").isEmpty());
    }

    @Test
    public void errorResultIsNotInjected() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.output = "{\"op\":\"search\",\"ok\":false,\"error\":\"未找到匹配的已索引项目\"}";

        assertTrue(provider(invoker, true).supplement("看下 ContextAssembler").isEmpty());
    }

    @Test
    public void graphErrorIsReportedWithItsReason() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.output = "{\"op\":\"search\",\"ok\":false,\"error\":"
                + "\"未找到匹配的已索引项目：请先用 op=index 建立图谱\"}";
        RecordingTrace trace = new RecordingTrace();

        assertTrue(provider(invoker, true, trace).supplement("看下 ContextAssembler").isEmpty());

        assertEquals(Collections.singletonList(0), trace.hits);
        // 「图谱报错」不能和「零命中」混为一谈：未索引 / 项目根路径失效都落在这一条上
        assertTrue(trace.details.get(0).get(0).contains("图谱返回错误"));
        assertTrue(trace.details.get(0).get(0).contains("未找到匹配的已索引项目"));
    }

    @Test
    public void disabledSupplementNeverQueries() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.output = HIT;

        assertTrue(provider(invoker, false).supplement("看下 ContextAssembler").isEmpty());
        assertTrue(invoker.names.isEmpty());
    }

    @Test
    public void repeatedQuestionQueriesGraphOnce() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.output = HIT;
        CodeGraphSupplement supplement = provider(invoker, true);

        assertEquals(1, supplement.supplement("看下 ContextAssembler").size());
        assertEquals(1, supplement.supplement("看下 ContextAssembler").size());
        assertEquals(1, invoker.names.size());
    }

    @Test
    public void invokerFailureIsSwallowedIntoNoSupplement() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.failure = new IllegalStateException("图谱进程起不来");

        assertTrue(provider(invoker, true).supplement("看下 ContextAssembler").isEmpty());
    }

    @Test
    public void identifierGluedToChineseIsUsedAsPatternAlone() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.output = HIT;

        // 汉字不是标识符字符：粘在类名旁边的中文不能进检索模式，否则整句永远命中不了
        assertEquals(1, provider(invoker, true)
                .supplement("帮我分析一下PackageRuleDescriptionService 这个类被那些类使用过").size());
        assertTrue(invoker.arguments.get(0).contains("\"pattern\":\"PackageRuleDescriptionService\""));
    }

    @Test
    public void naturalLanguageWordsNeverJoinThePattern() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.output = HIT;

        provider(invoker, true).supplement("please show me who uses PackageRuleDescriptionService");

        // 只取像代码的词：who / uses / show 是内容匹配的噪声来源，不进模式
        assertTrue(invoker.arguments.get(0).contains("\"pattern\":\"PackageRuleDescriptionService\""));

        RecordingInvoker snake = new RecordingInvoker();
        snake.output = HIT;
        provider(snake, true).supplement("看下 premium_calculation 的实现");

        // 蛇形 / 常量命名同样算标识符
        assertTrue(snake.arguments.get(0).contains("\"pattern\":\"premium_calculation\""));
    }

    @Test
    public void questionWithoutIdentifierSkipsGraphQuery() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.output = HIT;
        RecordingTrace trace = new RecordingTrace();

        // 纯中文整句里没有标识符：查了也是零命中，不如省掉这次 3 秒查询，并把原因说清楚
        assertTrue(provider(invoker, true, trace).supplement("这个接口的参数校验逻辑在哪里").isEmpty());
        assertTrue(invoker.names.isEmpty());
        assertEquals(Collections.singletonList(0), trace.hits);
        assertTrue(trace.details.get(0).get(0).contains("没有可检索的标识符"));

        RecordingInvoker english = new RecordingInvoker();
        english.output = HIT;
        assertTrue(provider(english, true).supplement("who uses the gateway").isEmpty());
        assertTrue(english.names.isEmpty());
    }

    @Test
    public void hitIsReportedToTraceSink() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.output = HIT;
        RecordingTrace trace = new RecordingTrace();

        provider(invoker, true, trace).supplement("看下 ContextAssembler 怎么装配上下文");

        assertEquals(Collections.singletonList("code_graph"), trace.sources);
        assertEquals(Collections.singletonList(1), trace.hits);
        // 明细首行带检索 pattern，其后是命中片段：未命中时看 pattern 就知道拿什么去查的图谱
        assertEquals(2, trace.details.get(0).size());
        assertTrue(trace.details.get(0).get(0).contains("pattern="));
        assertTrue(trace.details.get(0).get(0).contains("ContextAssembler"));
    }

    @Test
    public void zeroHitAndFailureAreReportedWithZeroHits() throws Exception {
        RecordingInvoker zeroHit = new RecordingInvoker();
        zeroHit.output = ZERO_HIT;
        RecordingTrace zeroTrace = new RecordingTrace();

        assertTrue(provider(zeroHit, true, zeroTrace).supplement("看下 ContextAssembler").isEmpty());
        assertEquals(Collections.singletonList(0), zeroTrace.hits);

        RecordingInvoker failing = new RecordingInvoker();
        failing.failure = new IllegalStateException("图谱进程起不来");
        RecordingTrace failureTrace = new RecordingTrace();

        assertTrue(provider(failing, true, failureTrace).supplement("看下 ContextAssembler").isEmpty());
        assertEquals(Collections.singletonList(0), failureTrace.hits);
        assertTrue(failureTrace.details.get(0).get(0).contains("调用失败"));
    }

    @Test
    public void repeatedQuestionIsReportedAsCacheHit() throws Exception {
        RecordingInvoker invoker = new RecordingInvoker();
        invoker.output = HIT;
        RecordingTrace trace = new RecordingTrace();
        CodeGraphSupplement supplement = provider(invoker, true, trace);

        supplement.supplement("看下 ContextAssembler");
        supplement.supplement("看下 ContextAssembler");

        // 第二次不再查图谱，但仍上报：否则控制台会看不到「这轮为什么没查」
        assertEquals(2, trace.sources.size());
        assertTrue(trace.details.get(1).get(0).contains("缓存"));
    }

    /** 临时配置文件名序号：同一个用例里可能多次构造设置，文件名固定会导致重复创建而报错。 */
    private int settingsSeq;

    private CodeGraphSupplement provider(IToolInvoker invoker, boolean supplementEnabled) throws Exception {
        return provider(invoker, supplementEnabled, null);
    }

    private CodeGraphSupplement provider(IToolInvoker invoker, boolean supplementEnabled,
            IContextTraceSink traceSink) throws Exception {
        return new CodeGraphSupplement(invoker, settings(supplementEnabled), traceSink);
    }

    private CodeGraphSettings settings(boolean supplementEnabled) throws Exception {
        File file = folder.newFile("clara-" + supplementEnabled + "-" + (settingsSeq++) + ".yml");
        String yaml = "codeGraph:\n  enabled: true\n  supplement: " + supplementEnabled
                + "\n  supplementMaxChars: 4000\n";
        Files.write(file.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        return CodeGraphSettings.load(file.toPath());
    }

    /** 记录上报内容的观测口：断言「打了哪些点、报了什么」用。 */
    private static final class RecordingTrace implements IContextTraceSink {

        private final List<String> sources = new ArrayList<String>();
        private final List<Integer> hits = new ArrayList<Integer>();
        private final List<List<String>> details = new ArrayList<List<String>>();

        @Override
        public void onTrace(String source, int hitCount, long elapsedMillis, List<String> rendered) {
            sources.add(source);
            hits.add(hitCount);
            details.add(new ArrayList<String>(rendered));
        }
    }

    /** 假工具调用器：记录每次调用的工具名与入参，按需要回结果或抛异常。 */
    private static final class RecordingInvoker implements IToolInvoker {

        private final List<String> names = Collections.synchronizedList(new ArrayList<String>());
        private final List<String> arguments = Collections.synchronizedList(new ArrayList<String>());
        private String output = "";
        private RuntimeException failure;

        @Override
        public ToolResult invoke(IToolInvocation invocation) {
            names.add(invocation.name());
            arguments.add(invocation.arguments());
            if (failure != null) {
                throw failure;
            }
            return new ToolResult(invocation.callId(), output);
        }
    }
}
