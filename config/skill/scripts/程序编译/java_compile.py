import json
import os
import re
import subprocess
import sys
import tempfile
import time

# 工具脚本按用途分目录存放，共享辅助模块位于 scripts 根目录，故先把 scripts 根加入模块搜索路径。
SCRIPTS_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_ROOT)

import _textio
import _toolmeta

# 强制标准输入/输出为 UTF-8：Java 侧按 UTF-8 读写子进程管道，避免中文 Windows 区域编码（GBK）致乱码。
_textio.force_utf8_stdio()

# 工具元数据（与执行体同文件）：框架以 `python java_compile.py --describe` 读取本段即得到工具声明。
_TOOL = {
    "id": "java_compile",
    "name": "java_compile",
    "type": "PYTHON",
    "description": (
        "用 javac 编译 Java 源码并回传结构化编译报错（文件/行/列/严重级别/消息，并附出错源码行）。"
        "入参 JSON：op(compile|version，缺省 compile)、"
        "sources(.java 文件路径数组，相对 CLARA_WORK_DIR 或 @repo 或绝对路径)、"
        "source_dir(目录，递归收集其下所有 .java；未给 sources 时使用)、"
        "classpath(依赖 classpath 条目数组，可为 jar 或目录，支持 @repo 前缀；现有工程通常需传)、"
        "output_dir(编译输出目录，缺省为临时目录，不污染工程)、"
        "release(目标版本，如 8，等价 javac --release)、"
        "encoding(源码编码，缺省 UTF-8，避免中文 Windows 默认 GBK 误报)、"
        "options(追加的 javac 原始参数数组，如 [\"-Xlint:all\"])、"
        "javac(javac 可执行文件路径，缺省优先取 JAVA_HOME/bin/javac，其次 PATH)、"
        "max_diagnostics(回传的诊断条数上限，默认 100)、max_sources(收集源文件数上限，默认 500)、"
        "timeout_seconds(超时秒数，默认 120，上限 600)。"
        "编译整个 Maven 工程请改用 run_command 执行 mvn 编译；本工具针对单批源码做快速语法/类型检查。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "op": {"type": "string", "enum": ["compile", "version"]},
            "sources": {"type": "array", "items": {"type": "string"}},
            "source_dir": {"type": "string"},
            "classpath": {"type": "array", "items": {"type": "string"}},
            "output_dir": {"type": "string"},
            "release": {"type": ["string", "integer", "number"]},
            "encoding": {"type": "string"},
            "options": {"type": "array", "items": {"type": "string"}},
            "javac": {"type": "string"},
            "max_diagnostics": {"type": "integer"},
            "max_sources": {"type": "integer"},
            "timeout_seconds": {"type": ["integer", "number"]},
        },
    },
    "metadata": {"domain": "java", "tags": "python,java,javac,compile,diagnostics"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

# 仓库根：scripts 根（见文件头部 SCRIPTS_ROOT）上溯三级即仓库根。
REPO_ROOT = os.path.normpath(os.path.join(SCRIPTS_ROOT, "..", "..", ".."))

# 工作根：启动脚本把「用户执行命令时所在目录」写入 CLARA_WORK_DIR；相对路径都相对它解析。
WORK_ROOT = os.environ.get("CLARA_WORK_DIR") or REPO_ROOT

# 仓库根相对前缀：以 @repo 开头时按仓库根解析，与「当前分析目录」（CLARA_WORK_DIR）区分开。
REPO_PREFIX = "@repo"

DEFAULT_ENCODING = "UTF-8"
DEFAULT_MAX_DIAGNOSTICS = 100
DEFAULT_MAX_SOURCES = 500
DEFAULT_TIMEOUT_SECONDS = 120.0
MAX_TIMEOUT_SECONDS = 600.0
RAW_OUTPUT_MAX_BYTES = 20000

# javac 诊断：`<路径>.java:<行>: <错误|警告>: <消息>`；同时兼容中文区域的「错误/警告」字样。
_LOCATED = re.compile(
    r"^(?P<file>.+?\.java):(?P<line>\d+):\s*(?P<severity>error|warning|错误|警告)\s*[:：]\s*(?P<message>.*)$")
# 无位置的诊断：`javac: file not found: ...` 等启动级错误，以及无文件行号的 error/warning 行。
_GENERIC = re.compile(r"^(?P<prefix>javac|error|warning|错误|警告)\s*[:：]\s*(?P<message>.+)$")


def resolve(path):
    # path 可为 "@repo" 前缀、绝对路径或相对工作根（CLARA_WORK_DIR）的路径。
    text = str(path)
    if text.startswith(REPO_PREFIX):
        rest = text[len(REPO_PREFIX):].lstrip("/\\")
        return os.path.join(REPO_ROOT, rest) if rest else REPO_ROOT
    return text if os.path.isabs(text) else os.path.join(WORK_ROOT, text)


def javac_path(spec):
    configured = spec.get("javac")
    if isinstance(configured, str) and configured.strip():
        return configured.strip()
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = os.path.join(java_home, "bin", "javac.exe" if os.name == "nt" else "javac")
        if os.path.exists(candidate):
            return candidate
    return "javac"


def collect_sources(spec):
    # 优先用显式 sources；否则递归收集 source_dir 下的 .java，按 max_sources 截断。
    declared = spec.get("sources")
    if isinstance(declared, list) and declared:
        return [resolve(item) for item in declared], False
    directory = spec.get("source_dir")
    if not isinstance(directory, str) or not directory.strip():
        raise ValueError("需要提供 sources（.java 文件数组）或 source_dir（源码目录）")
    root = resolve(directory)
    if not os.path.isdir(root):
        raise NotADirectoryError("源码目录不存在：" + root)
    max_sources = int(spec.get("max_sources") or DEFAULT_MAX_SOURCES)
    found = []
    truncated = False
    for current, dirs, names in os.walk(root):
        dirs[:] = sorted(name for name in dirs if not name.startswith("."))
        for name in sorted(names):
            if not name.endswith(".java"):
                continue
            found.append(os.path.join(current, name))
            if len(found) >= max_sources:
                truncated = True
                break
        if truncated:
            break
    return found, truncated


def build_command(spec, sources, output_dir):
    command = [javac_path(spec)]
    # 固定英文诊断，避免区域设置导致关键字本地化后不可解析。
    command += ["-J-Duser.language=en", "-J-Duser.country=US"]
    encoding = spec.get("encoding")
    command += ["-encoding", encoding.strip() if isinstance(encoding, str) and encoding.strip() else DEFAULT_ENCODING]
    if spec.get("release") is not None:
        command += ["--release", str(spec["release"])]
    classpath = spec.get("classpath")
    if isinstance(classpath, list) and classpath:
        command += ["-classpath", os.pathsep.join(resolve(entry) for entry in classpath)]
    command += ["-d", output_dir]
    extra = spec.get("options")
    if isinstance(extra, list):
        command += [str(item) for item in extra]
    return command + sources


def severity_of(value):
    return "warning" if value in ("warning", "警告") else "error"


def parse_diagnostics(text, limit):
    # 逐行扫描 javac 诊断；命中后把紧随的源码行与定位符行作为上下文一并回传。
    lines = text.splitlines()
    diagnostics = []
    total = 0
    errors = 0
    warnings = 0
    index = 0
    while index < len(lines):
        located = _LOCATED.match(lines[index])
        generic = None if located else _GENERIC.match(lines[index])
        if located is None and generic is None:
            index += 1
            continue
        total += 1
        if located is not None:
            severity = severity_of(located.group("severity"))
            entry = {"file": located.group("file"), "line": int(located.group("line")),
                     "severity": severity, "message": located.group("message").strip()}
            cursor = index + 1
            if cursor < len(lines) and lines[cursor].strip() and not _LOCATED.match(lines[cursor]):
                entry["source"] = lines[cursor].strip()
                cursor += 1
                if cursor < len(lines) and "^" in lines[cursor]:
                    entry["pointer"] = lines[cursor].rstrip()
        else:
            prefix = generic.group("prefix")
            severity = "error" if prefix == "javac" else severity_of(prefix)
            entry = {"file": None, "line": None, "severity": severity,
                     "message": generic.group("message").strip()}
        if severity == "warning":
            warnings += 1
        else:
            errors += 1
        if len(diagnostics) < limit:
            diagnostics.append(entry)
        index += 1
    return diagnostics, total, errors, warnings


def truncate(raw, max_bytes=RAW_OUTPUT_MAX_BYTES):
    if raw is None:
        return "", False
    truncated = len(raw) > max_bytes
    data = raw[:max_bytes] if truncated else raw
    try:
        text, _ = _textio.decode(data)
    except UnicodeDecodeError:  # noqa: BLE001 - 截断可能切断多字节字符，退回替换解码
        text = data.decode(_textio.DEFAULT_ENCODING, errors="replace")
    return text, truncated


def timeout_of(spec):
    seconds = float(spec.get("timeout_seconds") or DEFAULT_TIMEOUT_SECONDS)
    return min(max(seconds, 0.1), MAX_TIMEOUT_SECONDS)


def op_version(spec):
    javac = javac_path(spec)
    completed = subprocess.run([javac, "-version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               timeout=timeout_of(spec))
    stdout, _ = truncate(completed.stdout)
    stderr, _ = truncate(completed.stderr)
    return {"javac": javac, "exit_code": completed.returncode,
            "version": (stdout + stderr).strip()}


def op_compile(spec):
    sources, sources_truncated = collect_sources(spec)
    if not sources:
        raise ValueError("未找到任何 .java 源文件")
    output_dir = spec.get("output_dir")
    output_dir = resolve(output_dir) if isinstance(output_dir, str) and output_dir.strip() \
        else tempfile.mkdtemp(prefix="clara-javac-")
    os.makedirs(output_dir, exist_ok=True)

    command = build_command(spec, sources, output_dir)
    limit = int(spec.get("max_diagnostics") or DEFAULT_MAX_DIAGNOSTICS)
    started = time.time()
    result = {"command": " ".join(command), "sources": len(sources),
              "sources_truncated": sources_truncated, "output_dir": output_dir}
    try:
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   timeout=timeout_of(spec))
    except subprocess.TimeoutExpired as exception:  # noqa: PERF203 - 超时回传已产生的输出
        stdout, stdout_truncated = truncate(exception.stdout)
        stderr, stderr_truncated = truncate(exception.stderr)
        result.update({"status": "timeout", "exit_code": None, "stdout": stdout,
                       "stdout_truncated": stdout_truncated, "stderr": stderr,
                       "stderr_truncated": stderr_truncated,
                       "duration_ms": int((time.time() - started) * 1000),
                       "error": "javac 在 %s 秒内未结束，已终止子进程" % timeout_of(spec)})
        return result

    # javac 把诊断写到标准错误，故诊断以 stderr 为主、stdout 兜底。
    stderr, stderr_truncated = truncate(completed.stderr)
    stdout, stdout_truncated = truncate(completed.stdout)
    diagnostics, total, errors, warnings = parse_diagnostics(stderr + "\n" + stdout, limit)
    result.update({
        "status": "ok" if completed.returncode == 0 else "failed",
        "exit_code": completed.returncode,
        "error_count": errors,
        "warning_count": warnings,
        "diagnostic_count": total,
        "diagnostics_truncated": total > len(diagnostics),
        "diagnostics": diagnostics,
        "stderr": stderr,
        "stderr_truncated": stderr_truncated,
        "stdout": stdout,
        "stdout_truncated": stdout_truncated,
        "duration_ms": int((time.time() - started) * 1000),
    })
    return result


def main():
    raw = sys.stdin.read()
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except Exception as exception:  # noqa: BLE001 - 入参不可解析时也以结果回传，避免非零退出中断整轮
        print(json.dumps({"op": "", "error": "入参 JSON 解析失败：" + str(exception)}, ensure_ascii=False))
        return
    op = (spec.get("op") or "compile").strip()
    try:
        if op == "version":
            print(json.dumps({"op": op, **op_version(spec)}, ensure_ascii=False))
        elif op == "compile":
            print(json.dumps({"op": op, **op_compile(spec)}, ensure_ascii=False))
        else:
            print(json.dumps({"op": op, "error": "unknown op: " + str(op)}, ensure_ascii=False))
    except FileNotFoundError as exception:  # noqa: BLE001 - javac 不可用时给出可操作提示
        print(json.dumps({"op": op, "error": "找不到 javac：%s；请安装 JDK 并加入 PATH，"
                                             "或传入 javac 参数指定其绝对路径" % exception},
                         ensure_ascii=False))
    except Exception as exception:  # noqa: BLE001 - 以错误字段回传，保持协议不中断
        print(json.dumps({"op": op, "error": str(exception)}, ensure_ascii=False))


main()
