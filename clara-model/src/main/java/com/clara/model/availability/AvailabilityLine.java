package com.clara.model.availability;

import com.clara.enums.AvailabilityLevelEnum;
import com.clara.utils.StringUtils;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 可用性检查输出的单行内容：纯文本与状态级别。
 *
 * <p>是什么、在哪一层：{@code clara-model} 可用性检查包中的不可变值对象。检查只产出「文本 + 级别」，
 * 由调用方决定呈现方式——application 层据此为「可用 / 不可用 / 跳过」着色，本包不感知颜色控制码。
 *
 * <p>约定：{@link #getText()} 始终是纯文本，可直接写入日志文件；{@link #getLevel()} 只表达语义，
 * 与终端是否支持颜色无关。
 *
 * <p>两个渲染入口，产出完全一致：
 * <ul>
 *   <li>{@link #render(List)}：拿到整批结果后一次性渲染，列宽由结果自身推导；</li>
 *   <li>{@link #widths(List, List)} 预计算列宽 + {@link #line(ModelCheckResult, AvailabilityColumnWidths)} 逐条渲染。
 *       需要这条路径的原因：探测一个模型要等一次网络往返，应当是「检查完一个就输出一个」，而列宽必须先
 *       定下来，否则先输出的行与后输出的行对不齐；于是调用方先用「本地即可取得的信息」（别名、已解析的
 *       平台与模型）算好列宽，再逐条渲染。</li>
 * </ul>
 *
 * <p>对齐规则：按「状态、别名、平台与模型、结论」四列输出，各列宽度取显示宽度（由
 * {@link StringUtils#displayWidth(String)} 按「东亚全角占两列」计算）。状态列固定取三种状态标签的最大
 * 宽度，使列宽与「本批恰好出现哪些状态」无关，逐条渲染与整批渲染因此得到同样的排版。
 */
@Getter
public final class AvailabilityLine {

    /**
     * 行首缩进，与启动输出的其他条目一致。
     */
    private static final String INDENT = "  ";

    /**
     * 列间分隔。
     */
    private static final String GAP = "  ";

    /**
     * 检查通过的状态标签。
     */
    private static final String STATUS_AVAILABLE = "[可用]";

    /**
     * 跳过检查的状态标签。
     */
    private static final String STATUS_SKIPPED = "[跳过]";

    /**
     * 检查未通过的状态标签。
     */
    private static final String STATUS_UNAVAILABLE = "[不可用]";

    /**
     * 状态列宽度：固定取三种状态标签的最大显示宽度。
     */
    private static final int STATUS_WIDTH = StringUtils.displayWidth(STATUS_UNAVAILABLE);

    /**
     * 状态级别，非 null。
     * -- GETTER --
     *  读取状态级别。
     *
     * @return 状态级别，非 null

     */
    private final AvailabilityLevelEnum level;

    /**
     * 单行纯文本，非 null。
     * -- GETTER --
     *  读取单行纯文本。
     *
     * @return 文本，非 null

     */
    private final String text;

    /**
     * 创建单行输出。
     *
     * @param level 状态级别，非 null
     * @param text  单行纯文本，非 null
     */
    private AvailabilityLine(AvailabilityLevelEnum level, String text) {
        this.level = level;
        this.text = text;
    }

    /**
     * 创建普通信息行。
     *
     * @param text 单行纯文本，非 null
     * @return 级别为 {@link AvailabilityLevelEnum#INFO} 的输出行
     */
    public static AvailabilityLine info(String text) {
        return new AvailabilityLine(AvailabilityLevelEnum.INFO, text);
    }

    /**
     * 创建「模型可用」输出行。
     *
     * @param text 单行纯文本，非 null
     * @return 级别为 {@link AvailabilityLevelEnum#OK} 的输出行
     */
    public static AvailabilityLine ok(String text) {
        return new AvailabilityLine(AvailabilityLevelEnum.OK, text);
    }

    /**
     * 创建「跳过检查」输出行。
     *
     * @param text 单行纯文本，非 null
     * @return 级别为 {@link AvailabilityLevelEnum#WARN} 的输出行
     */
    public static AvailabilityLine warn(String text) {
        return new AvailabilityLine(AvailabilityLevelEnum.WARN, text);
    }

    /**
     * 创建「模型不可用」输出行。
     *
     * @param text 单行纯文本，非 null
     * @return 级别为 {@link AvailabilityLevelEnum#FAIL} 的输出行
     */
    public static AvailabilityLine fail(String text) {
        return new AvailabilityLine(AvailabilityLevelEnum.FAIL, text);
    }

    /**
     * 预计算列宽：状态列固定，别名与「平台与模型」列取传入文本的最大显示宽度。
     *
     * <p>传入的应当是「本次会涉及的全部行」的文本——包括那些还没探测、但已能从配置解析出来的行，
     * 否则先输出的行会按偏窄的列宽排版。
     *
     * @param aliases    本次全部行的别名，可为 null（视为空）
     * @param identities 与别名一一对应的「平台与模型」列文本，可为 null（视为空）
     * @return 可复用的列宽，非 null
     */
    public static AvailabilityColumnWidths widths(List<String> aliases, List<String> identities) {
        int aliasWidth = 0;
        if (aliases != null) {
            for (String alias : aliases) {
                aliasWidth = Math.max(aliasWidth, width(alias));
            }
        }
        int identityWidth = 0;
        if (identities != null) {
            for (String identity : identities) {
                identityWidth = Math.max(identityWidth, width(identity));
            }
        }
        return new AvailabilityColumnWidths(STATUS_WIDTH, aliasWidth, identityWidth);
    }

    /**
     * 把一批检查结果渲染成按列对齐的输出行，顺序与输入一致。
     *
     * <p>整批可用时用本方法；需要「检查完一个就输出一个」时改用 {@link #widths(List, List)} +
     * {@link #line(ModelCheckResult, AvailabilityColumnWidths)}，两者排版一致。
     *
     * @param results 检查结果，非 null
     * @return 与输入一一对应的输出行
     */
    public static List<AvailabilityLine> render(List<ModelCheckResult> results) {
        List<String> aliases = new ArrayList<>();
        List<String> identities = new ArrayList<>();
        for (ModelCheckResult result : results) {
            aliases.add(result.getAlias());
            identities.add(identity(result));
        }
        AvailabilityColumnWidths widths = widths(aliases, identities);
        List<AvailabilityLine> lines = new ArrayList<>();
        for (ModelCheckResult result : results) {
            lines.add(line(result, widths));
        }
        return lines;
    }

    /**
     * 用预计算好的列宽渲染单个检查结果。
     *
     * @param result 检查结果，非 null
     * @param widths 由 {@link #widths(List, List)} 得到的列宽，非 null
     * @return 该结果对应的输出行，非 null
     * @throws IllegalArgumentException widths 为 null 时
     */
    public static AvailabilityLine line(ModelCheckResult result, AvailabilityColumnWidths widths) {
        if (widths == null) {
            throw new IllegalArgumentException("widths must not be null");
        }
        StringBuilder text = new StringBuilder(INDENT)
                .append(StringUtils.padRight(status(result), widths.getStatusWidth())).append(GAP)
                .append(StringUtils.padRight(result.getAlias(), widths.getAliasWidth())).append(GAP)
                .append(StringUtils.padRight(identity(result), widths.getIdentityWidth()));
        String conclusion = conclusion(result);
        if (!conclusion.isEmpty()) {
            text.append(GAP).append(conclusion);
        }
        return new AvailabilityLine(level(result), text.toString());
    }

    /**
     * 拼「平台与模型」列文本。
     *
     * <p>调用方在预计算列宽时用它拼出与逐条渲染完全相同的文本，避免两处格式分叉。
     *
     * @param provider 平台名，可为 null
     * @param model    模型标识；为 null 表示配置未解析成功，此时该列为空串
     * @return 形如 {@code provider=DEEPSEEK model=deepseek-v4-pro} 的文本，或空串
     */
    public static String identity(String provider, String model) {
        return model == null ? "" : "provider=" + provider + " model=" + model;
    }

    /**
     * 取状态标签。
     *
     * @param result 检查结果，非 null
     * @return {@code [可用]}、{@code [跳过]} 或 {@code [不可用]}
     */
    private static String status(ModelCheckResult result) {
        if (result.isAvailable()) {
            return STATUS_AVAILABLE;
        }
        return result.isSkipped() ? STATUS_SKIPPED : STATUS_UNAVAILABLE;
    }

    /**
     * 取状态级别。
     *
     * @param result 检查结果，非 null
     * @return 可用为 {@link AvailabilityLevelEnum#OK}、跳过为 {@link AvailabilityLevelEnum#WARN}、
     *         其余为 {@link AvailabilityLevelEnum#FAIL}
     */
    private static AvailabilityLevelEnum level(ModelCheckResult result) {
        if (result.isAvailable()) {
            return AvailabilityLevelEnum.OK;
        }
        return result.isSkipped() ? AvailabilityLevelEnum.WARN : AvailabilityLevelEnum.FAIL;
    }

    /**
     * 取结果对应的「平台与模型」列文本。
     *
     * @param result 检查结果，非 null
     * @return 形如 {@code provider=DEEPSEEK model=deepseek-v4-pro} 的文本，或空串
     */
    private static String identity(ModelCheckResult result) {
        return identity(result.getProvider(), result.getModel());
    }

    /**
     * 取结论列：可用状态给出首字符响应时间，其余状态给出跳过或不可用的原因。
     *
     * @param result 检查结果，非 null
     * @return 结论文本；没有可展示内容时为空串
     */
    private static String conclusion(ModelCheckResult result) {
        if (!result.isAvailable()) {
            return StringUtils.isBlank(result.getDetail()) ? "" : result.getDetail();
        }
        String latency = "首字符响应=" + result.getFirstCharacterMillis() + "ms";
        return StringUtils.isBlank(result.getDetail()) ? latency : latency + GAP + result.getDetail();
    }

    /**
     * 取文本显示宽度；null 或空串按 0 计。
     *
     * @param value 待测量的文本，可为 null
     * @return 显示宽度，单位为列
     */
    private static int width(String value) {
        return StringUtils.isBlank(value) ? 0 : StringUtils.displayWidth(value);
    }
}
