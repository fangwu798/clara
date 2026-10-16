#!/usr/bin/env sh
# ============================================================
#  Clara 启动脚本（Linux / macOS / Git Bash）
#  用法：./clara.sh [模型配置文件，可选]
#  前置：JDK 8+ 已加入 PATH；jar 已构建：
#        mvn -q -pl clara-application -am package -DskipTests
#
#  启动前检查：
#   1) Python 运行环境（工具脚本依赖）；缺失时引导安装，并把解释器路径写入 PYTHON_PATH；
#   2) 凭据环境变量：config/model-providers 下每个配置声明的变量逐个检查，
#      同名变量只检查一次（deepseek 的两个别名共用一把 Key）；
#      未找到配置文件时退回主模型（defaultModel）；缺失时引导输入。
# ============================================================

# 记住用户启动脚本时所在的目录：即要分析的项目目录（Clara 以 CLARA_WORK_DIR 读取）。
export CLARA_WORK_DIR="$(pwd)"

# 切到脚本所在目录（仓库根），保证 config/ 可被定位。
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

# 以黄色打印告警（终端支持 ANSI；不支持时退化为普通文本）。
warn() {
    printf '\033[33m%s\033[0m\n' "$1"
}

# ---- 1) Python 运行环境检查 ----
detect_python() {
    if command -v python3 >/dev/null 2>&1; then
        command -v python3
    elif command -v python >/dev/null 2>&1; then
        command -v python
    fi
}

install_python() {
    if command -v apt-get >/dev/null 2>&1; then
        cmd="sudo apt-get update && sudo apt-get install -y python3"
    elif command -v dnf >/dev/null 2>&1; then
        cmd="sudo dnf install -y python3"
    elif command -v yum >/dev/null 2>&1; then
        cmd="sudo yum install -y python3"
    elif command -v apk >/dev/null 2>&1; then
        cmd="sudo apk add python3"
    elif command -v brew >/dev/null 2>&1; then
        cmd="brew install python3"
    else
        cmd=""
    fi
    if [ -z "$cmd" ]; then
        echo "        未识别包管理器，请手动安装 Python 3：https://www.python.org/downloads/"
        return
    fi
    printf "        是否现在自动安装 Python 3（%s）？[y/N]: " "$cmd"
    read -r ans
    case "$ans" in
        y|Y)
            echo "        执行：$cmd"
            eval "$cmd"
            ;;
        *)
            echo "        已跳过；可手动执行：$cmd"
            ;;
    esac
}

PYTHON_PATH="$(detect_python)"
if [ -z "$PYTHON_PATH" ]; then
    warn "[WARN] 未检测到 Python 运行环境（工具能力依赖 Python）。"
    install_python
    PYTHON_PATH="$(detect_python)"
fi
if [ -z "$PYTHON_PATH" ]; then
    echo "[ERROR] 仍未找到 Python，请手动安装后重试：https://www.python.org/downloads/"
    exit 1
fi
export PYTHON_PATH
echo "[OK] Python 运行环境：$PYTHON_PATH"

# ---- 2) 凭据环境变量检查 ----
# config/model-providers 下每个配置都声明一个凭据环境变量，启动时的可用性检查会探测全部配置，
# 所以这里也全量检查；多个别名可能共用同一个环境变量（deepseek-completion 与 deepseek-response
# 都用 CLARA_DEEPSEEK_API_KEY），同名变量只检查一次；未找到任何配置文件时退回只检查主模型。

# 记录本轮已检查的凭据环境变量：已检查过返回 0，首次出现返回 1。
checked_keys=""
key_checked() {
    case " $checked_keys " in
        *" $1 "*)
            return 0
            ;;
    esac
    checked_keys="$checked_keys $1"
    return 1
}

check_key_file() {
    cfg="$1"
    label="$2"
    if [ ! -f "$cfg" ]; then
        warn "[WARN] $label 未找到模型配置文件（$cfg），跳过 Key 检查。"
        return
    fi
    case "$(basename "$cfg")" in
        *.example.yml|*.example.yaml)
            # 样例文件不是真实配置，与 Java 侧扫描口径保持一致。
            return
            ;;
    esac
    if grep -qE '^[[:space:]]*apiKey[[:space:]]*:' "$cfg" 2>/dev/null; then
        echo "[INFO] $label 使用内联 apiKey，跳过环境变量检查（请确认该文件未被提交到版本库）。"
        return
    fi
    key_env="$(grep -E '^[[:space:]]*environmentVariable[[:space:]]*:' "$cfg" 2>/dev/null \
        | head -n1 | sed -E 's/^[^:]*:[[:space:]]*//' | tr -d ' \r"')"
    if [ -z "$key_env" ]; then
        echo "[INFO] $label 未声明 environmentVariable，跳过 Key 检查。"
        return
    fi
    if key_checked "$key_env"; then
        # 该环境变量已被更早的别名检查过，不再重复输出。
        return
    fi
    if [ -n "$(printenv "$key_env" 2>/dev/null)" ]; then
        echo "[OK] 已检测到 $label 的凭据环境变量 $key_env。"
        return
    fi
    warn "[WARN] 未检测到 $label 的凭据环境变量 $key_env。"
    warn "[提示] 若刚通过 shell 配置设置，请新开终端或重新 source 配置后再运行。"
    printf "请输入 %s 的值（直接回车跳过）: " "$key_env"
    read -r key_value
    if [ -n "$key_value" ]; then
        export "$key_env=$key_value"
        echo "[OK] 已为本次启动设置 $key_env（如需持久化，可把下面一行加入 shell 配置）："
        echo "        export $key_env=***"
    else
        warn "[WARN] 未输入 $key_env，相关模型调用可能因缺少凭据而失败。"
    fi
}

model_config_count=0
for cfg in config/model-providers/*.yml config/model-providers/*.yaml; do
    [ -f "$cfg" ] || continue
    model_config_count=$((model_config_count + 1))
    provider_alias="$(basename "$cfg")"
    provider_alias="${provider_alias%.yml}"
    provider_alias="${provider_alias%.yaml}"
    check_key_file "$cfg" "$provider_alias"
done

if [ "$model_config_count" -eq 0 ]; then
    # 没有模型配置文件：退回只检查主模型。
    model_config="$1"
    if [ -z "$model_config" ]; then
        default_model="$(grep -E '^[[:space:]]*defaultModel[[:space:]]*:' config/clara.yml 2>/dev/null \
            | head -n1 | sed -E 's/^[^:]*:[[:space:]]*//' | tr -d ' \r' | tr -d '"')"
        if [ -n "$default_model" ]; then
            if [ -f "config/model-providers/$default_model.yml" ]; then
                model_config="config/model-providers/$default_model.yml"
            elif [ -f "config/model-providers/$default_model.yaml" ]; then
                model_config="config/model-providers/$default_model.yaml"
            fi
        fi
    fi
    check_key_file "$model_config" "主模型"
fi

echo ""
# -Dfile.encoding=UTF-8 不是可选项：Milvus Java SDK 用 String.getBytes()（平台默认字符集）
# 编码全文检索（BM25）的正文，默认字符集不是 UTF-8 时正文会变成非法字节，
# Milvus 的 jieba 分词器会 panic 并让服务端整个 abort（历史召回随之不可用）。
exec java -Dfile.encoding=UTF-8 -jar clara.jar "$@"
