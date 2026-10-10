#!/usr/bin/env sh
# ============================================================
#  Clara 启动脚本（Linux / macOS / Git Bash）
#  用法：./run.sh [模型配置文件，可选]
#  前置：JDK 8+ 已加入 PATH；jar 已构建：
#        mvn -q -pl clara-application -am package -DskipTests
# ============================================================

# 切到脚本所在目录（仓库根），保证 config/ 可被定位
cd "$(dirname "$0")" || exit 1

if [ ! -f "clara.jar" ]; then
    echo "[ERROR] 未找到 clara.jar，请先在仓库根执行："
    echo "        mvn -q -pl clara-application -am package -DskipTests"
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    echo "[ERROR] 未找到 java，请安装 JDK 8+ 并加入 PATH。"
    exit 1
fi

exec java -jar clara.jar "$@"
