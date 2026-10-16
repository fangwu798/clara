package com.clara.codegraph;

import com.clara.config.CodeGraphSettings;
import com.clara.core.memory.IContextSupplementProvider;
import com.clara.core.memory.IContextTraceSink;
import com.clara.core.skill.IToolInvoker;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.dto.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代码知识图谱补充信息：每轮对话前先按提问查一次图谱，命中就把结构化事实注入 system 指令。
 *
 * <p>是什么、在哪一层：application 的上下文补充实现（包 {@code com.clara.codegraph}），实现 core 的
 * {@link IContextSupplementProvider}，由 {@code ApplicationBootstrap} 装配进上下文装配器。
 *
 * <p>为什么复用 {@code code_graph} 技能而不是自己拼 MCP 工具名：项目名要靠「项目根 = 当前分析目录」匹配、
 * 宿主路径还要按挂载根换算成容器内路径，这些判断都在技能脚本里（还会在未索引时给出可执行的导入提示）；
 * Java 侧重写一遍只会走偏。本类只负责「什么时候问、结果怎么进提示词」。
 *
 * <p>失败语义：图谱未部署、未索引当前目录、查询超时、返回空结果，一律返回空列表——不注入就等于「图谱里
 * 没有这份知识」，模型会按 {@code code_graph_guide} 的约定改用工具查本地文件。
 *
 * <p>生命周期与线程安全：无状态（除一张按输入缓存的小表），可跨线程共享；单轮查询耗时通常 2~6 秒，
 * 命中过一次的相同输入直接命中缓存。
 */
public final class CodeGraphSupplement implements IContextSupplementProvider {

    /**
     * 补充信息块的首行标记：模型据此确认这份信息来自图谱、可直接当事实用。
     */
    private static final String HEADER = "【代码知识图谱补充信息】";

    /**
     * 补在末尾的使用约定：图谱没覆盖的部分再查本地文件。
     */
    private static final String FOOTER = "（以上仅覆盖图谱已索引的部分；如果仍然缺信息，再用 file_ops / "
            + "text_search 等工具查本地文件，不要凭猜测回答。）";

    /**
     * 检索用技能名（本仓库的技能脚本，内部经 MCP 注册的执行入口访问图谱）。
     */
    private static final String SKILL_NAME = "code_graph";

    /**
     * 单次检索最多拼几个关键词：类名 / 方法名一般一两个就够，多了只会把无关内容带进补充信息。
     */
    private static final int MAX_KEYWORDS = 3;

    /**
     * 关键词最短长度：太短的词（id、of）匹配噪声大。
     */
    private static final int MIN_KEYWORD_LENGTH = 3;

    /** 解析技能返回的 JSON：用来判断「一条都没命中」，避免白注入一段空结果。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 知识源标识：宿主据此决定展示成什么名字。 */
    private static final String SOURCE = "code_graph";

    private final IToolInvoker toolInvoker;
    private final CodeGraphSettings settings;
    private final IContextTraceSink traceSink;
    private final Map<String, List<String>> cache = new ConcurrentHashMap<String, List<String>>();

    /**
     * 创建补充信息提供者。
     *
     * @param toolInvoker 工具调用入口（按名字路由到本地技能），可为 null（此时不查询）
     * @param settings    图谱开关，可为 null（按默认开关处理）
     */
    public CodeGraphSupplement(IToolInvoker toolInvoker, CodeGraphSettings settings) {
        this(toolInvoker, settings, null);
    }

    /**
     * 创建补充信息提供者并接入观测口。
     *
     * @param toolInvoker 工具调用入口（按名字路由到本地技能），可为 null（此时不查询）
     * @param settings    图谱开关，可为 null（按默认开关处理）
     * @param traceSink   上下文来源观测口，可为 null（不上报）
     */
    public CodeGraphSupplement(IToolInvoker toolInvoker, CodeGraphSettings settings,
            IContextTraceSink traceSink) {
        this.toolInvoker = toolInvoker;
        this.settings = settings == null ? CodeGraphSettings.defaults() : settings;
        this.traceSink = traceSink;
    }

    @Override
    public List<String> supplement(String userInput) {
        if (toolInvoker == null || !settings.isSupplementEnabled()) {
            return Collections.emptyList();
        }
        String pattern = pattern(userInput);
        if (pattern.isEmpty()) {
            trace(0, 0L, "提问里没有可检索的标识符（类名 / 方法名 / 字段名），本轮不查图谱");
            return Collections.emptyList();
        }
        List<String> cached = cache.get(pattern);
        if (cached != null) {
            trace(cached.isEmpty() ? 0 : 1, 0L, "pattern=" + pattern + "（命中本进程缓存，未重新检索）");
            return cached;
        }
        List<String> knowledge = query(pattern);
        cache.put(pattern, knowledge);
        return knowledge;
    }

    /**
     * 按关键词检索图谱，把命中的结果整理成一段补充信息。
     *
     * @param pattern 检索模式（关键词或关键词的正则或）
     * @return 补充信息列表；没命中或查询失败时为空列表，非 null
     */
    private List<String> query(String pattern) {
        long started = System.nanoTime();
        try {
            ToolCall call = new ToolCall();
            call.setId("code-graph-supplement");
            call.setType("function");
            ToolCallFunction function = new ToolCallFunction();
            function.setName(SKILL_NAME);
            // regex=true 必须带：图谱默认把 pattern 当字面量，多关键词拼出来的 "a|b" 会被整条拿去匹配，
            // 命中恒为 0（图谱只回一条 warning），补充信息就永远注不进去。
            function.setArguments("{\"op\":\"search\",\"regex\":true,\"pattern\":" + quote(pattern)
                    + ",\"purpose\":\"回答前先查代码知识图谱：按当前提问检索相关符号与文件\"}");
            call.setFunction(function);
            ToolResult result = toolInvoker.invoke(call);
            String payload = result == null ? null : result.getOutput();
            String failure = errorMessage(payload);
            if (failure != null) {
                // 图谱「报错」和「零命中」是两件事：报错时把原因打出来，否则观察者会以为只是没索引到
                trace(0, started, "pattern=" + pattern + "（图谱返回错误：" + preview(failure) + "）");
                return Collections.emptyList();
            }
            String body = extract(payload);
            if (body.isEmpty()) {
                trace(0, started, "pattern=" + pattern + "（图谱没有匹配到内容）");
                return Collections.emptyList();
            }
            trace(1, started, "pattern=" + pattern, preview(body));
            List<String> knowledge = new ArrayList<String>();
            knowledge.add(HEADER + "以下内容来自本机代码知识图谱（索引自当前工作目录，可直接作为事实使用，"
                    + "token 开销远小于读整份文件）：\n" + body + "\n" + FOOTER);
            return Collections.unmodifiableList(knowledge);
        } catch (RuntimeException failure) {
            // 图谱是增强能力：任何失败都只是「这次没有补充信息」，不能影响对话本身。
            trace(0, started, "pattern=" + pattern + "（调用失败：" + failure.getMessage() + "）");
            return Collections.emptyList();
        }
    }

    /**
     * 从技能返回的 JSON 里取出可用的检索结果文本；失败、空结果、报错都返回空串。
     *
     * @param payload 技能输出（JSON 文本），可为 null
     * @return 结果文本，非 null
     */
    private String extract(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return "";
        }
        String text = payload.trim();
        if (text.contains("\"error\"") || text.contains("\"ok\": false") || text.contains("\"ok\":false")) {
            return "";
        }
        if (!hasHits(text)) {
            return "";
        }
        int limit = settings.getSupplementMaxChars();
        if (text.length() > limit) {
            text = text.substring(0, limit) + "…";
        }
        // 结果整体是一份 JSON（{op, ok, result}），对模型来说直接读 JSON 也能用，这里不再拆结构，
        // 免得图谱版本换了字段名就把补充信息整段丢掉。
        return text;
    }

    /**
     * 从技能返回里取出错误原因：{@code ok=false} 或带 {@code error} 字段时返回原因，正常结果返回 null。
     *
     * <p>为什么要单独拆出来：图谱的失败（未索引当前目录、项目根路径失效、Cypher 语法错）原先和
     * 「零命中」走同一条路，观测行只会说「没有匹配到内容」——排查时会一路往「关键词不对」上想。
     *
     * @param payload 技能输出（JSON 文本），可为 null
     * @return 错误原因；不是错误时返回 null
     */
    private static String errorMessage(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (IOException notJson) {
            // 不是 JSON：交给 extract() 按文本嗅探兜底
            return null;
        }
        if (root.path("ok").isBoolean() && !root.path("ok").asBoolean()) {
            String reason = root.path("error").asText("");
            return reason.isEmpty() ? "图谱返回失败，但没带原因" : reason;
        }
        return root.path("error").isTextual() ? root.path("error").asText() : null;
    }

    /**
     * 判断这次检索是否真的命中了内容。
     *
     * <p>图谱对「零命中」也回一整份结构（约 1.5 KB）：照原样注入等于每轮白烧 token，还会让模型把
     * 「查过但没结果」误当成事实。计数项一个都读不到（结构变了 / 不是检索结果）时按「有内容」处理，
     * 宁可多带一段，也不因为图谱换字段名就把补充信息整个丢掉。
     *
     * @param payload 技能输出的 JSON 文本，非 null
     * @return true 表示有内容可注入
     */
    private static boolean hasHits(String payload) {
        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (IOException failure) {
            return true;
        }
        JsonNode result = root.path("result");
        if (!result.isObject()) {
            return true;
        }
        boolean sawCounter = false;
        int total = 0;
        for (String field : new String[] {"total_results", "total_grep_matches", "raw_match_count"}) {
            JsonNode value = result.path(field);
            if (value.isNumber()) {
                sawCounter = true;
                total += value.asInt();
            }
        }
        for (String field : new String[] {"rows", "results"}) {
            JsonNode value = result.path(field);
            if (value.isArray()) {
                sawCounter = true;
                total += value.size();
            }
        }
        JsonNode rawRows = result.path("raw_matches").path("rows");
        if (rawRows.isArray()) {
            sawCounter = true;
            total += rawRows.size();
        }
        return !sawCounter || total > 0;
    }

        /**
     * 从用户输入里挑检索关键词：只认「像代码标识符」的 ASCII 词（类名 / 方法名 / 字段名），
     * 汉字、标点、空白一律当分隔符。
     *
     * <p>为什么汉字必须当分隔符：{@code Character.isLetterOrDigit} 对汉字返回 true，于是
     * 「帮我分析一下PackageRuleDescriptionService」会被连成一个 token，整句再被拿去当检索模式；
     * 图谱按符号名与代码内容匹配，整句永远命中不了——现象就是「明明索引过，图谱却一直未命中」。
     *
     * <p>为什么只认「像代码」的词：图谱检索是内容匹配（{@code op=search} 走 {@code search_code}），
     * 把 who / uses / the 这类自然语言词一起带上会命中大片无关内容，把补充信息的 token 预算烧掉。
     * 因此只保留：首字母后含大写（驼峰 / 帕斯卡）、含下划线（常量 / 蛇形）、或字母数字混排的词。
     *
     * <p>一个都没有（纯中文提问、纯自然语言提问）时返回空串：本轮不查图谱，交给模型按
     * {@code code_graph_guide} 的约定自己带模式查，省掉一次注定零命中的查询。
     *
     * @param userInput 用户输入，可为 null
     * @return 检索模式；取不到时返回空串，非 null
     */
    private static String pattern(String userInput) {
        if (userInput == null) {
            return "";
        }
        String trimmed = userInput.trim();
        if (trimmed.length() < MIN_KEYWORD_LENGTH) {
            return "";
        }
        List<String> keywords = new ArrayList<String>();
        StringBuilder token = new StringBuilder();
        // 多走一格并在末尾补分隔符，让收尾的 token 也走同一处 flush，省掉循环外的一次重复调用
        for (int index = 0; index <= trimmed.length(); index++) {
            char current = index == trimmed.length() ? ' ' : trimmed.charAt(index);
            if (isKeywordChar(current)) {
                token.append(current);
                continue;
            }
            addKeyword(keywords, token.toString());
            token.setLength(0);
        }
        StringBuilder pattern = new StringBuilder();
        for (String keyword : keywords) {
            if (pattern.length() > 0) {
                pattern.append('|');
            }
            pattern.append(keyword);
        }
        return pattern.toString();
    }

    /**
     * 关键词字符：ASCII 字母 / 数字 / 下划线；其余（汉字、点、连字符、空白、标点）都是分隔符。
     * 顺带保证拼出来的模式里不含正则元字符——{@code op=search} 会按正则处理这个模式。
     */
    private static boolean isKeywordChar(char current) {
        return current >= 'a' && current <= 'z' || current >= 'A' && current <= 'Z'
                || current >= '0' && current <= '9' || current == '_';
    }

    /**
     * 收集一个候选关键词：够长了、像代码标识符、且还没收过才算数。
     */
    private static void addKeyword(List<String> keywords, String candidate) {
        if (keywords.size() >= MAX_KEYWORDS || !looksLikeIdentifier(candidate)
                || keywords.contains(candidate)) {
            return;
        }
        keywords.add(candidate);
    }

    /**
     * 判断一个词像不像代码标识符：首字母后含大写（驼峰 / 帕斯卡类名）、含下划线（常量 / 蛇形命名）、
     * 或字母数字混排。
     *
     * @param candidate 待判断的词，非 null
     * @return true 表示可拿去查图谱
     */
    private static boolean looksLikeIdentifier(String candidate) {
        if (candidate.length() < MIN_KEYWORD_LENGTH) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean codeLike = false;
        for (int index = 0; index < candidate.length(); index++) {
            char current = candidate.charAt(index);
            if (current == '_') {
                codeLike = true;
                continue;
            }
            if (current >= '0' && current <= '9') {
                hasDigit = true;
                continue;
            }
            hasLetter = true;
            if (index > 0 && current >= 'A' && current <= 'Z') {
                codeLike = true;
            }
        }
        return hasLetter && (codeLike || hasDigit);
    }

/**
     * 上报本次检索：未接入宿主时直接返回，上报本身失败也不影响对话。
     *
     * @param hits   可注入的条目数（0 表示未命中）
     * @param startedNanos 检索起点（传 0 表示本次没有真正检索）
     * @param details 明细，一行一条
     */
    private void trace(int hits, long startedNanos, String... details) {
        if (traceSink == null) {
            return;
        }
        long elapsedMillis = startedNanos == 0L ? 0L : (System.nanoTime() - startedNanos) / 1000000L;
        List<String> lines = new ArrayList<String>();
        for (String detail : details) {
            lines.add(detail);
        }
        try {
            traceSink.onTrace(SOURCE, hits, elapsedMillis, lines);
        } catch (RuntimeException ignored) {
            // 观测是旁路：宿主渲染失败不能影响本轮对话。
        }
    }

    /**
     * 把命中内容压成一行片段：换行与连续空白归一，超长截断。
     */
    private static String preview(String content) {
        String oneLine = content.trim().replaceAll("\\s+", " ");
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "…" : oneLine;
    }

    private static String quote(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '"' || current == '\\') {
                text.append('\\');
            }
            text.append(current);
        }
        return text.append('"').toString();
    }
}
