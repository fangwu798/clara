package com.clara.codegraph;

import com.clara.config.CodeGraphSettings;
import com.clara.core.memory.IContextSupplementProvider;
import com.clara.core.skill.IToolInvoker;
import com.clara.dto.tool.ToolCall;
import com.clara.dto.tool.ToolCallFunction;
import com.clara.dto.tool.ToolResult;

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
     * 单次检索最多拼几个关键词，避免把整句话当模式导致一条都匹配不上。
     */
    private static final int MAX_KEYWORDS = 3;

    /**
     * 关键词最短长度：太短的词（id、of）匹配噪声大。
     */
    private static final int MIN_KEYWORD_LENGTH = 3;

    private final IToolInvoker toolInvoker;
    private final CodeGraphSettings settings;
    private final Map<String, List<String>> cache = new ConcurrentHashMap<String, List<String>>();

    /**
     * 创建补充信息提供者。
     *
     * @param toolInvoker 工具调用入口（按名字路由到本地技能），可为 null（此时不查询）
     * @param settings    图谱开关，可为 null（按默认开关处理）
     */
    public CodeGraphSupplement(IToolInvoker toolInvoker, CodeGraphSettings settings) {
        this.toolInvoker = toolInvoker;
        this.settings = settings == null ? CodeGraphSettings.defaults() : settings;
    }

    @Override
    public List<String> supplement(String userInput) {
        if (toolInvoker == null || !settings.isSupplementEnabled()) {
            return Collections.emptyList();
        }
        String pattern = pattern(userInput);
        if (pattern.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> cached = cache.get(pattern);
        if (cached != null) {
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
        try {
            ToolCall call = new ToolCall();
            call.setId("code-graph-supplement");
            call.setType("function");
            ToolCallFunction function = new ToolCallFunction();
            function.setName(SKILL_NAME);
            function.setArguments("{\"op\":\"search\",\"pattern\":" + quote(pattern)
                    + ",\"purpose\":\"回答前先查代码知识图谱：按当前提问检索相关符号与文件\"}");
            call.setFunction(function);
            ToolResult result = toolInvoker.invoke(call);
            String payload = result == null ? null : result.getOutput();
            String body = extract(payload);
            if (body.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> knowledge = new ArrayList<String>();
            knowledge.add(HEADER + "以下内容来自本机代码知识图谱（索引自当前工作目录，可直接作为事实使用，"
                    + "token 开销远小于读整份文件）：\n" + body + "\n" + FOOTER);
            return Collections.unmodifiableList(knowledge);
        } catch (RuntimeException failure) {
            // 图谱是增强能力：任何失败都只是「这次没有补充信息」，不能影响对话本身。
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
        int limit = settings.getSupplementMaxChars();
        if (text.length() > limit) {
            text = text.substring(0, limit) + "…";
        }
        // 结果整体是一份 JSON（{op, ok, result}），对模型来说直接读 JSON 也能用，这里不再拆结构，
        // 免得图谱版本换了字段名就把补充信息整段丢掉。
        return text;
    }

    /**
     * 从用户输入里挑检索关键词：优先取长度足够的英文标识符（类名 / 方法名最常见），
     * 一个都没有（纯中文提问）时退回截断后的整句。
     *
     * @param userInput 用户输入，可为 null
     * @return 检索模式；取不到时返回空串，非 null
     */
    private static String pattern(String userInput) {
        if (userInput == null) {
            return "";
        }
        String trimmed = userInput.trim();
        if (trimmed.length() < 2) {
            return "";
        }
        List<String> keywords = new ArrayList<String>();
        StringBuilder token = new StringBuilder();
        for (int index = 0; index < trimmed.length(); index++) {
            char current = trimmed.charAt(index);
            if (Character.isLetterOrDigit(current) || current == '_') {
                token.append(current);
                continue;
            }
            addKeyword(keywords, token.toString());
            token.setLength(0);
        }
        addKeyword(keywords, token.toString());
        if (keywords.isEmpty()) {
            return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
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

    private static void addKeyword(List<String> keywords, String candidate) {
        if (keywords.size() >= MAX_KEYWORDS || !isIdentifier(candidate)) {
            return;
        }
        if (!keywords.contains(candidate)) {
            keywords.add(candidate);
        }
    }

    private static boolean isIdentifier(String candidate) {
        if (candidate.length() < MIN_KEYWORD_LENGTH) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            if (!Character.isLetterOrDigit(candidate.charAt(index)) && candidate.charAt(index) != '_') {
                return false;
            }
        }
        return true;
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
