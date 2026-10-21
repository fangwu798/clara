package com.clara.adapter.console;

import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/**
 * 带占位提示与状态行的行编辑器。
 *
 * <p>在 JLine 默认的「提示符 + 缓冲区」之后追加两段展示内容：
 * <ol>
 *   <li>缓冲区为空时，在光标位置追加灰色占位提示；开始输入后随缓冲区重绘自然消失；</li>
 *   <li>若配置了状态行供应器，在输入行下方追加一行状态（当前模型、模型 ID、工作目录）。</li>
 * </ol>
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
     * 创建行编辑器。
     *
     * @param terminal 系统终端，非 null
     * @param appName  应用名，用于历史文件等 JLine 内部标识
     * @throws IOException 终端初始化失败时
     */
    ClaraLineReader(Terminal terminal, String appName) throws IOException {
        super(terminal, appName);
    }

    /**
     * 设置输入行下方的状态行。
     *
     * @param statusLine 状态行供应器，可为 null（不显示）；返回 null 或空串时同样不显示
     */
    void setStatusLine(Supplier<String> statusLine) {
        this.statusLine = statusLine;
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
        String status = statusLine == null ? null : statusLine.get();
        if (status != null && !status.isEmpty()) {
            display.append("\n").append(AttributedString.fromAnsi(status));
        }
        return display.toAttributedString();
    }
}