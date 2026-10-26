package com.clara.adapter.console;

import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;
import org.jline.utils.Status;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * 带占位提示与状态行的行编辑器。
 *
 * <p>在 JLine 默认的「提示符 + 缓冲区」之后追加展示内容：
 * <ol>
 *   <li>缓冲区为空时，在光标位置追加灰色占位提示；开始输入后随缓冲区重绘自然消失；</li>
 *   <li>若终端支持滚动区域，状态行（当前模型、模型 ID、工作目录）固定在终端最底部；否则随输入行一起绘制。</li>
 * </ol>
 *
 * <p>为什么固定在底部：模型思考与工具执行期间会持续输出，随输入行绘制的状态行会被输出冲掉；
 * JLine 的 {@link Status} 通过滚动区域把最后一行留给状态栏，执行期间始终可见，且行编辑器会自动
 * 让出这一行，不会与输入行重叠。
 *
 * <p>状态行由供应器实时计算，因此 {@code /model} 切换模型后，下一次输入即可看到新模型，无需重建编辑器。
 * 状态行允许包含 ANSI 颜色码，由 {@link AttributedString#fromAnsi(String)} 解析。
 */
final class ClaraLineReader extends LineReaderImpl {

    /**
     * 输入框为空时显示的占位提示。
     */
    private static final String PLACEHOLDER = "输入任务，Enter 发送 · Shift+Enter 换行 · ↑/↓ 历史";

    /**
     * 占位提示样式：亮黑色即终端中的灰色，避免与用户实际输入混淆。
     */
    private static final AttributedStyle PLACEHOLDER_STYLE =
            AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT + AttributedStyle.BLACK);

    /**
     * 输入行下方状态行的供应器；为 null 时不显示状态行。
     */
    private Supplier<String> statusLine;

    /**
     * 终端底部固定状态栏；终端不支持滚动区域时为 null，退回「随输入行一起绘制」。
     */
    private final Status bottomStatus;

    /**
     * 创建行编辑器。
     *
     * @param terminal 系统终端，非 null
     * @param appName  应用名，用于历史文件等 JLine 内部标识
     * @throws IOException 终端初始化失败时
     */
    ClaraLineReader(Terminal terminal, String appName) throws IOException {
        super(terminal, appName);
        this.bottomStatus = createBottomStatus(terminal);
    }

    /**
     * 设置输入行下方的状态行。
     *
     * @param statusLine 状态行供应器，可为 null（不显示）；返回 null 或空串时同样不显示
     */
    void setStatusLine(Supplier<String> statusLine) {
        this.statusLine = statusLine;
        refreshStatus();
    }

    /**
     * 立即刷新底部状态栏：模型切换后在下一次读取输入前生效。
     */
    void refreshStatus() {
        if (bottomStatus == null) {
            return;
        }
        String status = statusText();
        if (status == null || status.isEmpty()) {
            bottomStatus.update(Collections.<AttributedString>emptyList());
            return;
        }
        bottomStatus.update(Collections.singletonList(AttributedString.fromAnsi(status)));
    }

    /**
     * 当前终端是否支持固定底部状态栏。
     *
     * @return 支持时为 true
     */
    boolean hasBottomStatus() {
        return bottomStatus != null;
    }

    /**
     * 读取当前状态文本：供应器异常时视为不显示，避免影响输入。
     *
     * @return 状态文本；不显示时为 null
     */
    private String statusText() {
        if (statusLine == null) {
            return null;
        }
        try {
            return statusLine.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 创建底部固定状态栏；终端不支持滚动区域时返回 null。
     *
     * <p>{@link Status} 依赖滚动区域、光标保存 / 恢复与光标定位能力，并要求终端尺寸有效；缺任意一项都无法
     * 在持续输出时固定最后一行，此时退回随输入行绘制。
     *
     * @param terminal 系统终端，非 null
     * @return 底部状态栏；不支持时为 null
     */
    private static Status createBottomStatus(Terminal terminal) {
        if (terminal.getStringCapability(InfoCmp.Capability.change_scroll_region) == null
                || terminal.getStringCapability(InfoCmp.Capability.save_cursor) == null
                || terminal.getStringCapability(InfoCmp.Capability.restore_cursor) == null
                || terminal.getStringCapability(InfoCmp.Capability.cursor_address) == null) {
            return null;
        }
        Size size = terminal.getSize();
        if (size.getRows() <= 0 || size.getRows() >= 1000 || size.getColumns() <= 0 || size.getColumns() >= 1000) {
            return null;
        }
        return Status.getStatus(terminal);
    }

    /**
     * 在默认显示内容后追加占位提示与状态行。
     *
     * @param secondaryPrompts 多行输入的续行提示，非 null
     * @return 含占位提示与状态行的显示内容
     */
    @Override
    public AttributedString getDisplayedBufferWithPrompts(List<AttributedString> secondaryPrompts) {
        AttributedString base = super.getDisplayedBufferWithPrompts(secondaryPrompts);
        AttributedStringBuilder display = new AttributedStringBuilder().append(base);
        if (getBuffer().length() == 0) {
            display.append(new AttributedString(PLACEHOLDER, PLACEHOLDER_STYLE));
        }
        // 底部状态栏可用时由 Status 自己绘制，这里不能再追加一份，否则会显示两行。
        String status = bottomStatus == null ? statusText() : null;
        if (status != null && !status.isEmpty()) {
            display.append("\n").append(AttributedString.fromAnsi(status));
        }
        return display.toAttributedString();
    }
}
