"""代码知识图谱工具：读代码时优先查本地知识图谱，图谱没有的再从本地文件读。

知识图谱由 codebase-memory-mcp（独立进程，纯 C 静态二进制，随 release 发布）维护：
它把仓库索引成 SQLite 里的节点 / 边（函数、类、导入、HTTP 路由及其关系），并可回答
「某文件有哪些声明」「某符号的调用方 / 被调用方」「按名字 / 正则找符号」等问题，
以上问题的答案通常比整文件读取少两三个数量级的 token。

本脚本是「图谱优先、文件兜底」的落点：
- op=read 逐文件先取图谱的文件大纲（声明清单）；该文件不在图谱里（未索引 / 不在索引范围 /
  图谱不可用）或图谱对它没有任何声明时，才回退为本地文件读取（与 file_ops 同口径的路径解析、
  编码识别与截断）。散文 / 文档类文件（.md / .txt 等）不查图谱、直接返回原文：图谱对它们最多
  只给章节标题，大纲不足以替代正文。
- 其余 op（index/status/search/outline/snippet/trace/query）是图谱能力的直通调用，
  给工作流与模型在「需要结构化信息」时使用。

调用方式与其它工具脚本一致：stdin 读 JSON 入参，stdout 打印 JSON 结果；
图谱二进制与图谱不存在时不会报错退出，而是按「图谱不可用」处理（read 自动回退文件）。
"""

import glob
import json
import os
import shutil
import subprocess
import sys

# 工具脚本按用途分目录存放，共享辅助模块位于 scripts 根目录，故先把 scripts 根加入模块搜索路径。
SCRIPTS_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_ROOT)

import _textio
import _toolmeta

# 强制标准输入/输出为 UTF-8：Java 侧按 UTF-8 读写子进程管道。
_textio.force_utf8_stdio()

# 工具元数据（与执行体同文件）：框架以 `python code_graph.py --describe` 读取本段即得到工具声明。
_TOOL = {
    "id": "code_graph",
    "name": "code_graph",
    "type": "PYTHON",
    "description": "代码知识图谱工具：读代码时优先使用本工具（图谱里能拿到文件大纲、符号定义、调用关系，token 开销远小于整文件读取）；图谱未覆盖或不可用时 op=read 会自动回退为本地文件读取；.md/.txt 这类散文文件不走图谱、直接返回原文。入参 JSON：op(index|status|search|outline|snippet|trace|query|read)、file_path(仓库相对路径，outline 用)、paths(路径或通配列表，read 用；元素可写 * ? [ ] 与 ** 递归)、pattern(名字 / 文本片段，search 用)、label(节点标签，如 Function / Class)、qualified_name(限定名，snippet 用)、function_name(函数名，trace 用)、direction(inbound|outbound|both，trace 用；inbound 查调用方、outbound 查被调用方)、depth(1-15，trace 用)、query(只读 Cypher，query 用)、project(图谱中的项目名，缺省按当前分析目录匹配)、repo_path(索引目标目录，index 用，缺省为当前分析目录)、max_bytes(单文件回退读取的截断字节，默认 20000)、max_files(展开后读取的文件数上限，默认 200)。返回：{op, source(graph|file|graph+file), files:[{path, source, outline|content}], ...}；其中 source 明确标注每条信息来自图谱还是本地文件。另需填写 purpose(请务必填写：一句话简短说明本次调用该工具的目的，会展示给用户)。",
    "parameters": {
        "type": "object",
        "properties": {
            "purpose": {"type": "string"},
            "op": {"type": "string",
                   "enum": ["index", "status", "search", "outline", "snippet", "trace", "query", "read"]},
            "file_path": {"type": "string"},
            "paths": {"type": "array", "items": {"type": "string"}},
            "pattern": {"type": "string"},
            "label": {"type": "string"},
            "qualified_name": {"type": "string"},
            "function_name": {"type": "string"},
            "direction": {"type": "string", "enum": ["inbound", "outbound", "both"],
                          "description": "op=trace 的调用图方向：inbound=调用方（谁调用了它），"
                                         "outbound=被调用方（它调用了谁），both=双向（默认）。"},
            "depth": {"type": "integer"},
            "query": {"type": "string"},
            "project": {"type": "string"},
            "repo_path": {"type": "string"},
            "base_dir": {"type": "string"},
            "max_bytes": {"type": "integer"},
            "max_files": {"type": "integer"},
        },
        "required": ["op"],
    },
    "metadata": {"domain": "code", "tags": "graph,code,search,index,knowledge"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

# 仓库根：scripts 根（见文件头部 SCRIPTS_ROOT）上溯三级即仓库根。
REPO_ROOT = os.path.normpath(os.path.join(SCRIPTS_ROOT, "..", "..", ".."))

# 工作根：启动脚本把「用户执行命令时所在目录」写入 CLARA_WORK_DIR，即本轮要分析的项目目录。
WORK_ROOT = os.environ.get("CLARA_WORK_DIR") or REPO_ROOT

# 仓库根相对前缀：与本仓库其它脚本（file_ops / dir_tree）保持一致，便于按 @repo 读取随应用发布的文件。
REPO_PREFIX = "@repo"

# 图谱二进制的位置来源：环境变量 → config/mcp 的 MCP 注册项 → PATH。三者都取不到即视为「图谱不可用」。
BINARY_ENV = "CLARA_CBM_BINARY"
MCP_CONFIG_DIR = os.path.join(REPO_ROOT, "config", "mcp")
SERVER_KEY = "codebase-memory-mcp"

# trace_path 的 --direction 只认 inbound / outbound / both：同时接受「调用方 / 被调用方」这类通俗说法，
# 免得工作流或模型按直觉传 callers / callees 时整条命令被 CLI 判成非法入参而白白失败一次。
TRACE_DIRECTIONS = {
    "inbound": "inbound", "callers": "inbound", "caller": "inbound", "调用方": "inbound",
    "outbound": "outbound", "callees": "outbound", "callee": "outbound", "被调用方": "outbound",
    "both": "both", "双向": "both",
}

# 单次查询超时与建索引超时：查询应秒回，建索引要读整个仓库，故分开给。
CLI_TIMEOUT_SECONDS = 180
INDEX_TIMEOUT_SECONDS = 1800

# 回退为本地文件读取时的默认截断字节与文件数上限（与 file_ops.read_many 默认值一致）。
DEFAULT_MAX_BYTES = 20000
DEFAULT_MAX_FILES = 200

# 路径中出现任一字符即按 glob 展开。
GLOB_CHARS = "*?["

# 散文 / 文档类扩展名：图谱对这类文件最多只给出章节标题，大纲既不如原文有用，还要多花一次 CLI 调用，
# 所以 op=read 见到它们直接读原文，不去问图谱（要单看标题结构请显式用 op=outline）。
PROSE_EXTENSIONS = {
    "md", "markdown", "mdx", "rst", "adoc", "asciidoc", "txt", "text", "csv", "tsv", "log",
}

# 递归展开目录时跳过的噪声目录（与 file_ops / dir_tree 一致）。
DEFAULT_SKIP_DIRS = [
    ".git", ".idea", ".m2", "target", "node_modules", "dist", "build",
    "logs", "volumes", "__pycache__", ".clara",
]


def resolve_root(base):
    # base 可为 "@repo" 前缀（仓库根相对）、绝对路径或相对工作根（CLARA_WORK_DIR）的路径。
    if base.startswith(REPO_PREFIX):
        rest = base[len(REPO_PREFIX):].lstrip("/\\")
        return os.path.join(REPO_ROOT, rest) if rest else REPO_ROOT
    return base if os.path.isabs(base) else os.path.join(WORK_ROOT, base)


def resolve(path, base):
    if path and path.startswith(REPO_PREFIX):
        rest = path[len(REPO_PREFIX):].lstrip("/\\")
        return os.path.join(REPO_ROOT, rest) if rest else REPO_ROOT
    root = resolve_root(base)
    if not path:
        return root
    return path if os.path.isabs(path) else os.path.join(root, path)


def relative_to_work(path):
    # 图谱按「仓库相对路径」寻址，故把绝对路径换算成相对工作根的路径（跨盘等算不出时退回原值）。
    try:
        rel = os.path.relpath(path, WORK_ROOT)
    except ValueError:
        return path
    return rel.replace("\\", "/")


def is_prose(path):
    # 判断是否为散文 / 文档类文件：只看扩展名，不看内容，保证同一次 read 的判定稳定可预期。
    name = os.path.basename(path)
    dot = name.rfind(".")
    if dot <= 0:
        return False
    return name[dot + 1:].lower() in PROSE_EXTENSIONS


def graph_relative(path, root):
    # 把绝对路径换算成图谱认的「项目内相对路径」；项目根未知、跨盘或不在项目内时返回 None，
    # 由调用方退回按当前分析目录换算（显式指定 project 的场景拿不到项目根）。
    if not root or not path:
        return None
    try:
        relative = os.path.relpath(path, root)
    except ValueError:
        return None
    if relative.startswith(".."):
        return None
    return relative.replace("\\", "/")


def config_binary():
    # 从 config/mcp/*.json 里取本服务端注册的 command[0]：安装脚本会把绝对路径写在这里，脚本据此复用同一份配置。
    if not os.path.isdir(MCP_CONFIG_DIR):
        return None
    for name in sorted(os.listdir(MCP_CONFIG_DIR)):
        if not name.lower().endswith(".json"):
            continue
        try:
            with open(os.path.join(MCP_CONFIG_DIR, name), "r", encoding="utf-8") as handle:
                payload = json.load(handle)
        except (OSError, ValueError):
            continue
        servers = payload.get("mcpServers") if isinstance(payload, dict) else None
        if not isinstance(servers, dict):
            continue
        entry = servers.get(SERVER_KEY)
        if not isinstance(entry, dict):
            continue
        command = entry.get("command")
        if isinstance(command, list) and command:
            candidate = str(command[0]).strip()
        elif isinstance(command, str) and command.strip():
            candidate = command.strip()
        else:
            continue
        if candidate:
            return candidate
    return None


def resolve_binary():
    # 返回可执行的图谱二进制路径；找不到返回 None（调用方按「图谱不可用」处理，不报错）。
    for candidate in (os.environ.get(BINARY_ENV), config_binary(), "codebase-memory-mcp"):
        if not candidate:
            continue
        if os.path.isfile(candidate):
            return candidate
        found = shutil.which(candidate)
        if found:
            return found
    return None


def clean_text(text):
    # 图谱进程每次启动都会往 stderr 打一行内存分配器告警（level=warn ...），它跟本次调用结果无关，
    # 混进错误信息会让模型误以为调用本身出了问题，这里统一剔除。
    lines = [line for line in (text or "").splitlines() if not line.strip().startswith("level=")]
    return "\n".join(lines).strip()


def run_cli(binary, tool, flags=None, payload=None, timeout=CLI_TIMEOUT_SECONDS, format_json=True):
    """执行一次 `codebase-memory-mcp cli <tool>`，返回 (ok, 文本)。

    参数优先用命令行开关（与官方 README 中的示例一致）；开关调用失败时再用「stdin 传 JSON 入参」重试一次
    （官方对个别工具的开关名未在文档中给出，但两条通道都会生成同一份工具入参），两条都失败才返回失败。
    format_json=False 用于本身没有 --format 开关的工具（官方目前只有 index_repository 如此），
    此时两条通道都不带该开关。
    """
    tail = ["--format", "json"] if format_json else []
    attempts = []
    if flags is not None:
        attempts.append((["cli", tool] + list(flags) + tail, None))
    if payload is not None:
        attempts.append((["cli", tool] + tail, json.dumps(payload, ensure_ascii=False)))
    if not attempts:
        attempts.append((["cli", tool] + tail, None))

    last_error = ""
    for args, stdin_text in attempts:
        try:
            completed = subprocess.run(
                [binary] + args,
                input=stdin_text,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
        except (OSError, subprocess.SubprocessError) as exception:  # noqa: PERF203 - 逐个通道尝试
            last_error = str(exception)
            continue
        if completed.returncode == 0:
            return True, clean_text(completed.stdout)
        last_error = clean_text(completed.stderr or completed.stdout) or ("exit " + str(completed.returncode))
    return False, last_error


def as_json(text):
    # CLI 的 --format json 输出为 JSON 文本；解析失败时返回 None，由调用方决定如何呈现原始文本。
    if not text:
        return None
    try:
        return json.loads(text)
    except ValueError:
        start = text.find("{")
        end = text.rfind("}")
        if start >= 0 and end > start:
            try:
                return json.loads(text[start:end + 1])
            except ValueError:
                return None
        return None


def list_projects(binary):
    ok, text = run_cli(binary, "list_projects")
    if not ok:
        return []
    payload = as_json(text)
    projects = payload.get("projects") if isinstance(payload, dict) else None
    return projects if isinstance(projects, list) else []


def canonical_path(path):
    # 比较路径前统一过一遍 realpath：图谱存的是规范化长路径，而 CLARA_WORK_DIR 可能是 8.3 短名
    # （形如 C:/Users/ADMINI~1/...），只做 normpath 会匹配不上，导致明明索引过却回退去读本地文件。
    if not path:
        return ""
    try:
        resolved = os.path.realpath(path)
    except (OSError, ValueError):
        resolved = path
    return os.path.normcase(os.path.normpath(resolved))


def project_root(project):
    # 项目根路径：图谱 list_projects 的字段名可能随版本变动，这里按已知候选依次取。
    for key in ("root_path", "root", "path", "repo_path", "indexed_path", "directory"):
        value = project.get(key)
        if isinstance(value, str) and value:
            return value
    return None


def match_project(projects, wanted):
    # 先精确匹配索引根；再退一步按「当前分析目录落在某个已索引项目内 / 包住某个已索引项目」匹配，
    # 这样在仓库子目录里启动也能用上图谱。匹配不到就返回 None，让调用方老实地读本地文件——
    # 这里刻意不做「只有一个项目就直接用」的兜底：那会让「当前目录没索引」时白跑一路 CLI 探测。
    # 返回 {"name": 项目名, "root": 项目根}；root 用于把文件绝对路径换算成图谱认的项目内相对路径。
    fallback = None
    for project in projects:
        if not isinstance(project, dict):
            continue
        root = project_root(project)
        if not root:
            continue
        canonical = canonical_path(root)
        if not canonical:
            continue
        if canonical == wanted:
            return {"name": project.get("name"), "root": root}
        if fallback is None and (wanted.startswith(canonical + os.sep) or canonical.startswith(wanted + os.sep)):
            fallback = {"name": project.get("name"), "root": root}
    return fallback


def project_of(spec, binary):
    # 图谱的多数工具都要求 --project：显式给了就用它（此时项目根未知，路径只能按当前分析目录换算），
    # 否则按「索引根 = 当前分析目录」自动匹配；返回 {"name", "root"}，匹配不到返回 None 由调用方回退。
    if not binary:
        return None
    explicit = spec.get("project")
    projects = list_projects(binary)
    if explicit:
        for project in projects:
            if isinstance(project, dict) and project.get("name") == explicit:
                return {"name": explicit, "root": project_root(project)}
        return {"name": explicit, "root": None}
    return match_project(projects, canonical_path(WORK_ROOT))


def normalize_direction(value):
    # 把入参里的方向说法归一成 trace_path 认的 inbound / outbound / both；未给或认不出时按 both（CLI 默认值）。
    if not value:
        return "both"
    return TRACE_DIRECTIONS.get(str(value).strip().lower(), "both")


def expand_targets(paths, base, max_files):
    # 把「路径 / 通配 / 目录」展开为真实存在的文件列表；返回 [(绝对路径, 仓库相对路径)]。
    targets = []
    seen = set()
    for raw in paths:
        candidate = resolve(raw, base)
        matches = []
        if any(char in raw for char in GLOB_CHARS):
            matches = [path for path in glob.glob(candidate, recursive=True) if os.path.isfile(path)]
        elif os.path.isdir(candidate):
            matches = [os.path.join(candidate, name) for name in sorted(os.listdir(candidate))
                       if os.path.isfile(os.path.join(candidate, name))]
        elif os.path.isfile(candidate):
            matches = [candidate]
        for path in matches:
            absolute = os.path.abspath(path)
            if absolute in seen:
                continue
            seen.add(absolute)
            targets.append((absolute, relative_to_work(absolute)))
            if len(targets) >= max_files:
                return targets
    return targets


def local_read(path, max_bytes):
    # 回退读取：与 file_ops 同口径的编码识别 + 按字节截断，读完直接返回文本。
    #
    # 多读 4 个字节做编码识别样本：样本若恰好切在多字节字符中间，UTF-8 判定会失败并落到 GB18030 等中文编码，
    # 于是整段文本变成乱码；单个 UTF-8 序列最长 4 字节，多读即可避免这种误判。
    # 截断仍按字节进行，解码时以 errors="ignore" 丢掉被切断的半个字符，保证输出是合法文本。
    try:
        with open(path, "rb") as handle:
            raw = handle.read(max_bytes + 4)
    except OSError as exception:
        return {"error": "读取失败：" + str(exception)}
    if _textio.is_binary(raw):
        return {"error": "二进制文件，已跳过（如需读取请改用对应的解析工具）"}
    truncated = len(raw) > max_bytes
    encoding = _textio.detect_encoding(raw) or _textio.DEFAULT_ENCODING
    raw = raw[:max_bytes]
    try:
        text = raw.decode(encoding, errors="ignore")
    except LookupError:
        text = raw.decode(_textio.DEFAULT_ENCODING, errors="ignore")
    if truncated:
        text += "\n[已截断：仅返回前 " + str(max_bytes) + " 字节]"
    return {"content": text}


def graph_outline(binary, project_name, rel_path):
    # 取单个文件的声明大纲；该文件不在图谱里（未索引 / 被忽略 / 索引范围外）时返回 None，由调用方回退读文件；图谱认得该文件、却给不出任何声明时同样返回 None。
    flags = ["--project", project_name, "--file-path", rel_path]
    ok, text = run_cli(binary, "get_file_outline", flags, {"project": project_name, "file_path": rel_path})
    if not ok:
        return None
    payload = as_json(text)
    if isinstance(payload, dict):
        # 只有真的拿到声明才用图谱：.gitignore / mcp.json / clara.bat 这类文件图谱认得，却一条声明都没有，
        # 空大纲没有任何信息量，必须交回调用方去读原文，否则这些文件的内容会被整段丢掉。
        for key in ("outline", "declarations", "symbols", "items", "rows", "tree"):
            value = payload.get(key)
            if value:
                return value
        return None
    if isinstance(payload, list):
        return payload or None
    if not payload:
        # 无结构化大纲时退回原始文本；文本为空或明确表示“没有”时按未覆盖处理。
        stripped = (text or "").strip()
        if not stripped or stripped in ("{}", "[]") or "not found" in stripped.lower():
            return None
        return stripped


def op_index(spec, binary):
    repo_path = os.path.abspath(resolve(spec.get("repo_path") or "", spec.get("base_dir") or ""))
    if not os.path.isdir(repo_path):
        return {"op": "index", "error": "目录不存在：" + repo_path}
    # index_repository 没有 --format 开关，带上会被 CLI 判成未知开关，故关掉自动拼接。
    ok, text = run_cli(binary, "index_repository", ["--repo-path", repo_path], {"repo_path": repo_path},
                       timeout=INDEX_TIMEOUT_SECONDS, format_json=False)
    return {"op": "index", "repo_path": repo_path, "ok": ok, "result": as_json(text) or text}


def simple_op(op, tool, flags, payload, binary, timeout=CLI_TIMEOUT_SECONDS):
    ok, text = run_cli(binary, tool, flags, payload, timeout=timeout)
    return {"op": op, "ok": ok, "result": as_json(text) or text}


def op_read(spec, binary):
    paths = spec.get("paths") or ([spec.get("path")] if spec.get("path") else [])
    if isinstance(paths, str):
        paths = [paths]
    if not paths:
        return {"op": "read", "error": "缺少 paths（要读取的文件路径或通配）"}
    base = spec.get("base_dir") or ""
    max_files = int(spec.get("max_files") or DEFAULT_MAX_FILES)
    max_bytes = int(spec.get("max_bytes") or DEFAULT_MAX_BYTES)
    targets = expand_targets(paths, base, max_files)

    project = project_of(spec, binary)
    project_name = project.get("name") if project else None
    project_root = project.get("root") if project else None
    files = []
    from_graph = 0
    for absolute, rel in targets:
        # 图谱按「项目内相对路径」寻址；在仓库子目录里启动时它与当前分析目录的相对路径并不相同，
        # 所以先按项目根换算一次，换不出来（项目根未知 / 跨盘）才退回按当前分析目录算出的相对路径。
        graph_path = graph_relative(absolute, project_root) or rel
        outline = None
        if project_name and not is_prose(absolute):
            outline = graph_outline(binary, project_name, graph_path)
        if outline is not None:
            files.append({"path": rel, "source": "graph", "outline": outline})
            from_graph += 1
        else:
            entry = {"path": rel, "source": "file"}
            entry.update(local_read(absolute, max_bytes))
            files.append(entry)

    if not targets:
        source = "none"
        note = "没有匹配到任何文件；请确认路径 / 通配写法，或先 op=index 建立图谱。"
    elif from_graph == len(targets):
        source = "graph"
        note = "以上信息全部来自本地代码知识图谱（op=outline 可看原始声明清单）。"
    elif from_graph == 0:
        source = "file"
        if binary is None:
            note = ("图谱不可用（未安装 / 未配置 CLARA_CBM_BINARY 或 config/mcp），已全部回退为本地文件读取；"
                    "需要图谱能力请先执行 deploy/codebase-memory-mcp/install.ps1。")
        elif not project_name:
            note = ("图谱里没有匹配当前分析目录的项目，已全部回退为本地文件读取；"
                    "可先 op=index 索引当前目录，或用 project 显式指定项目名。")
        else:
            note = "图谱未覆盖这些文件（多为未索引的新文件），已回退为本地文件读取。"
    else:
        source = "graph+file"
        note = "部分文件取自图谱（source=graph），图谱未覆盖的已回退本地文件（source=file）。"

    result = {"op": "read", "source": source, "targets": len(targets),
              "graph_files": from_graph, "files": files, "note": note,
              "project": project_name, "graph_available": bool(binary)}
    if len(targets) >= max_files:
        result["truncated"] = True
    return result


def main():
    raw = sys.stdin.read()
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except ValueError as exception:
        print(json.dumps({"error": "入参 JSON 解析失败：" + str(exception)}, ensure_ascii=False))
        return

    op = (spec.get("op") or "read").strip()
    binary = resolve_binary()

    # index 之外的操作都需要图谱可用；不可用时 read 自动回退文件，其余 op 明确回报原因。
    if binary is None and op != "read":
        print(json.dumps({
            "op": op, "ok": False,
            "error": ("未找到 codebase-memory-mcp 可执行文件：请设置 " + BINARY_ENV +
                      "，或在 config/mcp/*.json 中注册该服务端，或把它放进 PATH；"
                      "安装脚本见 deploy/codebase-memory-mcp/install.ps1。"),
        }, ensure_ascii=False))
        return

    # 图谱的多数工具都要求 --project：入参没给时按「索引根 = 当前分析目录」自动匹配，匹配不到再退回唯一项目。
    needs_project = op in ("search", "outline", "snippet", "trace", "query")
    project = project_of(spec, binary) if needs_project else None
    project_name = project.get("name") if project else None
    if needs_project and not project_name:
        print(json.dumps({
            "op": op, "ok": False,
            "error": ("未找到匹配的已索引项目：请先用 op=index 为当前分析目录建立图谱，"
                      "或用 project 指定 list_projects 返回的项目名。"),
        }, ensure_ascii=False))
        return

    try:
        if op == "read":
            result = op_read(spec, binary)
        elif op == "index":
            result = op_index(spec, binary)
        elif op == "status":
            result = simple_op("status", "list_projects", [], None, binary)
        elif op == "search":
            pattern = spec.get("pattern") or ""
            if spec.get("label"):
                result = simple_op("search", "search_graph",
                                   ["--project", project_name, "--name-pattern", pattern, "--label", spec.get("label")],
                                   {"project": project_name, "name_pattern": pattern, "label": spec.get("label")}, binary)
            else:
                result = simple_op("search", "search_code",
                                   ["--project", project_name, "--pattern", pattern],
                                   {"project": project_name, "pattern": pattern}, binary)
        elif op == "outline":
            file_path = spec.get("file_path") or ""
            result = simple_op("outline", "get_file_outline",
                               ["--project", project_name, "--file-path", file_path],
                               {"project": project_name, "file_path": file_path}, binary)
        elif op == "snippet":
            name = spec.get("qualified_name") or ""
            result = simple_op("snippet", "get_code_snippet",
                               ["--project", project_name, "--qualified-name", name],
                               {"project": project_name, "qualified_name": name}, binary)
        elif op == "trace":
            function_name = spec.get("function_name") or ""
            direction = normalize_direction(spec.get("direction"))
            flags = ["--project", project_name, "--function-name", function_name, "--direction", direction]
            payload = {"project": project_name, "function_name": function_name, "direction": direction}
            if spec.get("depth"):
                flags += ["--depth", str(spec.get("depth"))]
                payload["depth"] = spec.get("depth")
            result = simple_op("trace", "trace_path", flags, payload, binary)
        elif op == "query":
            query = spec.get("query") or ""
            result = simple_op("query", "query_graph",
                               ["--project", project_name, "--query", query],
                               {"project": project_name, "query": query}, binary)
        else:
            result = {"error": "不支持的 op：" + op}
    except (OSError, subprocess.SubprocessError) as exception:
        result = {"op": op, "error": "调用图谱失败：" + str(exception)}

    print(json.dumps(result, ensure_ascii=False))


main()