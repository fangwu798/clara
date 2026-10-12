package com.clara.adapter.console;

/**
 * 启动参数：把命令行识别（全量日志 / 关闭颜色 / 指定配置文件）从入口主流程中剥离出来。
 *
 * <p>是什么、在哪一层：application 的边界适配层（{@code com.clara.adapter.console}）的启动参数值对象；
 * 入口只消费解析结果，不再内联参数遍历与判定逻辑。
 *
 * <p>识别规则：{@code -v} / {@code --full} / {@code --verbose} 开启全量日志；{@code --no-color} 关闭颜色；
 * 其余「首个」非空参数视为模型配置文件路径。未识别的多余参数忽略。
 */
public final class LaunchOptions {

    private final String configFile;
    private final boolean fullLogging;
    private final String forcedColor;

    private LaunchOptions(String configFile, boolean fullLogging, String forcedColor) {
        this.configFile = configFile;
        this.fullLogging = fullLogging;
        this.forcedColor = forcedColor;
    }

    /**
     * 解析命令行参数。
     *
     * @param args 命令行参数，可为 null
     * @return 解析结果，非 null
     */
    public static LaunchOptions parse(String[] args) {
        String configFile = null;
        boolean fullLogging = false;
        String forcedColor = null;
        if (args != null) {
            for (String arg : args) {
                if (arg == null || arg.trim().isEmpty()) {
                    continue;
                }
                String value = arg.trim();
                if ("-v".equals(value) || "--full".equals(value) || "--verbose".equals(value)) {
                    fullLogging = true;
                } else if ("--no-color".equals(value)) {
                    forcedColor = "never";
                } else if (configFile == null) {
                    configFile = value;
                }
            }
        }
        return new LaunchOptions(configFile, fullLogging, forcedColor);
    }

    /**
     * 模型配置文件路径（由命令行直接指定）；未指定时 null。
     */
    public String configFile() {
        return configFile;
    }

    /**
     * 是否以全量日志模式启动。
     */
    public boolean fullLogging() {
        return fullLogging;
    }

    /**
     * 命令行强制的颜色取值（如 {@code never}）；未指定时 null。
     */
    public String forcedColor() {
        return forcedColor;
    }
}
