package com.clara.usage;

import com.clara.kernel.message.delta.TokenUsage;

import java.util.Map;

/**
 * 能力用量台账：把图片、视频与代码图谱的消耗从对话 token 中拆出来单独累计。
 *
 * <p>是什么、在哪一层：application 的用量统计组件（包 {@code com.clara.usage}），由对话入口与工作流
 * 动作共享同一个实例。对话 token 仍由原有的上下文估算与调用统计展示；本台账只回答「图片生成了几张 /
 * 视频任务消耗多少 token / 图谱查了多少次」。
 *
 * <p>口径说明：
 * <ul>
 *   <li>图片：平台可能只返回生成张数（{@code usage.generated_images}），token 字段缺失时按「平台未返回」
 *       展示，不臆造数值；</li>
 *   <li>视频：方舟异步任务在终态返回 {@code usage.completion_tokens / total_tokens}，按任务累计；</li>
 *   <li>图谱：{@code code_graph} 是 MCP 静态分析，不产生模型 token；这里统计查询次数与返回字符数，
 *       其内容会进入对话输入，已包含在对话 token 中，不做重复累加。</li>
 * </ul>
 *
 * <p>线程安全：所有读写方法同步；工作流节点可能并行执行，台账允许多线程同时记录。
 */
public final class CapabilityUsageLedger {

    /** 图片生成张数。 */
    private long imageCount;
    /** 图片调用累计输入 token（平台未返回时为 0）。 */
    private long imageInputTokens;
    /** 图片调用累计输出 token。 */
    private long imageOutputTokens;
    /** 图片调用累计总 token。 */
    private long imageTotalTokens;

    /** 视频任务数。 */
    private long videoTasks;
    /** 视频任务累计输入 token（方舟当前不返回，保留口径）。 */
    private long videoInputTokens;
    /** 视频任务累计输出 token。 */
    private long videoOutputTokens;
    /** 视频任务累计总 token。 */
    private long videoTotalTokens;

    /** 图谱查询次数。 */
    private long graphQueries;
    /** 图谱返回内容的累计字符数。 */
    private long graphChars;

    /**
     * 记录一次图片生成。
     *
     * @param usage           本次调用的 token 用量，可为 null（平台未返回）
     * @param generatedImages 平台返回的生成张数，可为 null
     */
    public synchronized void recordImage(TokenUsage usage, Integer generatedImages) {
        int images = generatedImages == null ? 0 : Math.max(0, generatedImages);
        imageCount += images;
        if (usage != null) {
            imageInputTokens += usage.getInputTokens();
            imageOutputTokens += usage.getOutputTokens();
            imageTotalTokens += total(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens());
        }
    }

    /**
     * 记录一次视频任务。
     *
     * @param usage 平台终态返回的用量映射，可为 null / 空
     */
    public synchronized void recordVideo(Map<String, Object> usage) {
        videoTasks++;
        if (usage == null || usage.isEmpty()) {
            return;
        }
        long input = number(usage, "prompt_tokens", "input_tokens");
        long output = number(usage, "completion_tokens", "output_tokens");
        long total = number(usage, "total_tokens");
        videoInputTokens += input;
        videoOutputTokens += output;
        videoTotalTokens += total > 0L ? total : input + output;
    }

    /**
     * 记录一次代码图谱查询。
     *
     * @param returnedChars 图谱返回内容的字符数，负数视为 0
     */
    public synchronized void recordGraph(long returnedChars) {
        graphQueries++;
        graphChars += Math.max(0L, returnedChars);
    }

    /**
     * 清空台账（开始新对话时调用）。
     */
    public synchronized void reset() {
        imageCount = 0L;
        imageInputTokens = 0L;
        imageOutputTokens = 0L;
        imageTotalTokens = 0L;
        videoTasks = 0L;
        videoInputTokens = 0L;
        videoOutputTokens = 0L;
        videoTotalTokens = 0L;
        graphQueries = 0L;
        graphChars = 0L;
    }

    /**
     * 渲染本次会话的能力用量报告。
     *
     * @return 多行文本，非 null
     */
    public synchronized String report() {
        StringBuilder text = new StringBuilder("能力用量（本次会话）：");
        text.append('\n').append("  图片：").append(imageCount).append(" 张");
        if (imageTotalTokens > 0L || imageInputTokens > 0L || imageOutputTokens > 0L) {
            text.append("；tokens input=").append(imageInputTokens)
                    .append("，output=").append(imageOutputTokens)
                    .append("，total=").append(imageTotalTokens);
        } else {
            text.append("；平台未返回 token");
        }
        text.append('\n').append("  视频：").append(videoTasks).append(" 个任务");
        if (videoTotalTokens > 0L || videoInputTokens > 0L || videoOutputTokens > 0L) {
            text.append("；completion=").append(videoOutputTokens)
                    .append("，total=").append(videoTotalTokens);
        } else {
            text.append("；平台未返回 token");
        }
        text.append('\n').append("  图谱：").append(graphQueries).append(" 次查询，返回 ")
                .append(graphChars).append(" 字符（MCP 图谱不产生 token；内容计入对话输入）");
        return text.toString();
    }

    /**
     * 图片用量的单行摘要，供工作流进度展示。
     *
     * @param usage           本次用量，可为 null
     * @param generatedImages 生成张数，可为 null
     * @return 单行文本，非 null
     */
    public static String describeImage(TokenUsage usage, Integer generatedImages) {
        int images = generatedImages == null ? 0 : Math.max(0, generatedImages);
        StringBuilder text = new StringBuilder(images + " 张");
        if (usage == null || total(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens()) <= 0L) {
            return text.append("，平台未返回 token").toString();
        }
        return text.append("，tokens input=").append(usage.getInputTokens())
                .append("，output=").append(usage.getOutputTokens())
                .append("，total=").append(total(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens()))
                .toString();
    }

    /**
     * 视频用量的单行摘要，供工作流进度展示。
     *
     * @param usage 平台用量映射，可为 null / 空
     * @return 单行文本，非 null
     */
    public static String describeVideo(Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            return "平台未返回 token";
        }
        long output = number(usage, "completion_tokens", "output_tokens");
        long total = number(usage, "total_tokens");
        if (output <= 0L && total <= 0L) {
            return "平台未返回 token";
        }
        return "completion=" + output + "，total=" + (total > 0L ? total : output);
    }

    private static long total(int input, int output, Integer total) {
        long explicit = total == null ? 0L : total.longValue();
        return explicit > 0L ? explicit : (long) input + output;
    }

    private static long number(Map<String, Object> usage, String... keys) {
        for (String key : keys) {
            Object value = usage.get(key);
            if (value instanceof Number) {
                return Math.max(0L, ((Number) value).longValue());
            }
        }
        return 0L;
    }
}
