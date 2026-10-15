import fnmatch
import glob
import json
import os
import sys

# 工具脚本按用途分目录存放，共享辅助模块位于 scripts 根目录，故先把 scripts 根加入模块搜索路径。
SCRIPTS_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_ROOT)

import _textio
import _toolmeta

# 强制标准输入/输出为 UTF-8：Java 侧按 UTF-8 读写子进程管道，避免中文 Windows 区域编码（GBK）致乱码。
_textio.force_utf8_stdio()

# 工具元数据（与执行体同文件）：框架以 `python file_ops.py --describe` 读取本段即得到工具声明。
_TOOL = {
    "id": "file_ops",
    "name": "file_ops",
    "type": "PYTHON",
    "description": "对指定目录下的文件执行新增/读取/追加/替换/删除/存在性/列举/递归扫描/批量读取等操作。入参 JSON：op(write|read|append|replace|delete|exists|list|scan|read_many)、path(相对 base_dir 或绝对路径)、base_dir(可选根目录，缺省为当前分析目录 CLARA_WORK_DIR，即执行 run.bat 时所在目录；以 @repo 开头表示按仓库根解析，用于读取随应用发布的固定文件如 config/standards 下的开发规范)、encoding(可选；读取时默认自动识别文件编码，如识别不准再显式指定（gbk、utf-8-sig 等）；写入新文件默认 utf-8)、content、find、replace；scan 额外支持 include/exclude(glob 列表)、max_files(默认 200)、max_depth(默认 6)、with_size；read_many 额外支持 paths(路径列表，元素可写通配模式，如 config/standards/* 一次读取整个目录，支持 * ? [] 与 ** 递归)、max_bytes(单文件截断字节，默认 20000)、max_files(展开后读取的文件数上限，默认 200)。适用于对单个或少量文件做精确读写与目录列举；若要一次性读取整个仓库并整理文档，请改用 run_workflow 工作流。另需填写 purpose(请务必填写：一句话简短说明本次调用该工具的目的，会展示给用户)。",
    "parameters": {
        "type": "object",
        "properties": {
            "purpose": {"type": "string"},
            "op": {"type": "string", "enum": ["write", "read", "append", "replace", "delete",
                                             "exists", "list", "scan", "read_many"]},
            "path": {"type": "string"},
            "base_dir": {"type": "string"},
            "encoding": {"type": "string"},
            "content": {"type": "string"},
            "find": {"type": "string"},
            "replace": {"type": "string"},
            "include": {"type": "array", "items": {"type": "string"}},
            "exclude": {"type": "array", "items": {"type": "string"}},
            "max_files": {"type": "integer"},
            "max_depth": {"type": "integer"},
            "with_size": {"type": "boolean"},
            "paths": {"type": "array", "items": {"type": "string"}},
            "max_bytes": {"type": "integer"},
        },
        "required": ["op"],
    },
    "metadata": {"domain": "sample", "tags": "file,python,filesystem,scan"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

# 写入文件时的默认编码：入参 encoding 未指定时按 UTF-8 写；读取时则自动识别（见 _textio.decode）。
DEFAULT_ENCODING = _textio.DEFAULT_ENCODING

# 仓库根：scripts 根（见文件头部 SCRIPTS_ROOT）上溯三级即仓库根。
REPO_ROOT = os.path.normpath(os.path.join(SCRIPTS_ROOT, "..", "..", ".."))

# 工作根：启动脚本 run.bat / run.sh 会把「用户执行命令时所在目录」写入环境变量 CLARA_WORK_DIR，
# 该目录即本轮要分析的项目目录；相对 base_dir 与默认 base 都相对它解析。未设置时退回仓库根，
# 保持开发 / 测试场景与既有行为一致（此时等价于仓库根与 config/sample/workspace）。
WORK_ROOT = os.environ.get("CLARA_WORK_DIR") or REPO_ROOT

# 默认工作区：优先取 CLARA_WORK_DIR（当前分析目录），否则 <repo>/config/sample/workspace。
DEFAULT_BASE = os.environ.get("CLARA_WORK_DIR") or os.path.join(REPO_ROOT, "config", "sample", "workspace")

# 递归扫描时默认跳过的噪声目录（构建产物、版本库、依赖等）。
DEFAULT_SKIP_DIRS = [
    ".git", ".idea", ".m2", "target", "node_modules", "dist", "build",
    "logs", "volumes", "__pycache__", ".clara",
]

# read_many 的路径通配符：路径中出现任一字符即按 glob 展开（* ? [] 均可，** 表示跨目录递归）。
GLOB_CHARS = "*?["

# read_many 展开通配后的文件数上限（与 scan 的 max_files 默认值一致），防止模式写得过宽读爆上下文。
DEFAULT_READ_MAX_FILES = 200


# 仓库根相对前缀：base_dir / path 以 "@repo" 开头时按仓库根解析，用于读取随应用一起发布的固定文件
# （如前后端开发规范），与「当前分析目录」（CLARA_WORK_DIR）区分开。
REPO_PREFIX = "@repo"


def resolve_root(base):
    # base 可为 "@repo" 前缀（仓库根相对）、绝对路径或相对工作根（CLARA_WORK_DIR）的路径。
    if base.startswith(REPO_PREFIX):
        rest = base[len(REPO_PREFIX):].lstrip("/\\")
        return os.path.join(REPO_ROOT, rest) if rest else REPO_ROOT
    return base if os.path.isabs(base) else os.path.join(WORK_ROOT, base)


def resolve(path, base):
    # path 可为 "@repo" 前缀（仓库根相对）、绝对路径或相对 base 的路径。
    if path and path.startswith(REPO_PREFIX):
        rest = path[len(REPO_PREFIX):].lstrip("/\\")
        return os.path.join(REPO_ROOT, rest) if rest else REPO_ROOT
    root = resolve_root(base)
    if not path:
        return root
    return path if os.path.isabs(path) else os.path.join(root, path)


def read_encoding(spec):
    # 读取时：入参 encoding 为显式指定；未指定则返回 None，交由 _textio 自动识别。
    encoding = spec.get("encoding")
    return encoding.strip() if isinstance(encoding, str) and encoding.strip() else None


def write_encoding(spec):
    # 写入时：入参 encoding 优先，缺省 UTF-8（新写文件无从识别，需取默认）。
    encoding = spec.get("encoding")
    return encoding.strip() if isinstance(encoding, str) and encoding.strip() else DEFAULT_ENCODING


def matches(rel_path, name, include, exclude):
    # include 为空表示不限制；rel_path 与 name 任一命中即视为匹配，便于同时支持 "*.java" 与 "src/**"。
    if include and not any(
            fnmatch.fnmatch(rel_path, pattern) or fnmatch.fnmatch(name, pattern)
            for pattern in include):
        return False
    if exclude and any(
            fnmatch.fnmatch(rel_path, pattern) or fnmatch.fnmatch(name, pattern)
            for pattern in exclude):
        return False
    return True


def scan(spec, target):
    # 递归列举目录下的文件（相对 target 的路径），支持 glob 过滤与数量/深度上限。
    include = spec.get("include") or []
    exclude = spec.get("exclude") or []
    skip_dirs = set(spec.get("skip_dirs") or DEFAULT_SKIP_DIRS)
    max_files = int(spec.get("max_files") or 200)
    max_depth = int(spec.get("max_depth") or 6)
    with_size = bool(spec.get("with_size"))

    files = []
    truncated = False
    base_depth = target.rstrip(os.sep).count(os.sep)
    for current, dirs, names in os.walk(target):
        dirs[:] = sorted([name for name in dirs
                          if name not in skip_dirs and not name.startswith(".")])
        if current.rstrip(os.sep).count(os.sep) - base_depth >= max_depth:
            dirs[:] = []
        for name in sorted(names):
            full = os.path.join(current, name)
            rel = os.path.relpath(full, target).replace(os.sep, "/")
            if not matches(rel, name, include, exclude):
                continue
            entry = {"path": rel}
            if with_size:
                try:
                    entry["size"] = os.path.getsize(full)
                except OSError:
                    entry["size"] = None
            files.append(entry)
            if len(files) >= max_files:
                truncated = True
                break
        if truncated:
            break
    return {"root": target, "files": files, "count": len(files), "truncated": truncated}


def read_many(spec, base):
    # 批量读取文件内容：paths 支持通配模式；逐文件自动识别编码，单文件按 max_bytes 截断，单个失败不影响其余。
    paths = spec.get("paths") or []
    if isinstance(paths, str):
        paths = [paths]
    max_bytes = int(spec.get("max_bytes") or 20000)
    max_files = int(spec.get("max_files") or DEFAULT_READ_MAX_FILES)
    explicit = read_encoding(spec)

    targets = expand_paths(paths, base)
    overflow = len(targets) > max_files
    if overflow:
        targets = targets[:max_files]

    items = []
    total = 0
    for path in targets:
        item = {"path": path}
        if any(char in path for char in GLOB_CHARS) and not os.path.exists(resolve(path, base)):
            # 用通配模式时自己报错：Windows 上直接把 "…/*" 当路径去读会抛出难懂的系统错误。
            item["error"] = "通配模式未匹配到任何文件，请检查目录与扩展名"
            items.append(item)
            continue
        try:
            full = resolve(path, base)
            size = os.path.getsize(full)
            raw = _textio.read_bytes(full)
            if explicit is None and _textio.detect_encoding(raw) is None:
                item["error"] = "疑似二进制文件，已跳过（如确需读取请显式指定 encoding）"
                items.append(item)
                continue
            text, used = _textio.decode(raw, explicit)
            truncated = len(text) > max_bytes
            if truncated:
                text = text[:max_bytes]
            item["content"] = text
            item["size"] = size
            item["encoding"] = used
            item["truncated"] = truncated
            total += len(text)
        except Exception as exception:  # noqa: BLE001 - 单个文件失败以错误字段回传
            item["error"] = str(exception)
        items.append(item)
    return {"files": items, "count": len(items), "total_chars": total, "truncated": overflow}


def expand_paths(paths, base):
    # 展开 read_many 的 paths：不含通配符的原样返回；含通配符的按 base 展开成具体文件。
    # 展开结果按路径排序并去重（相对 base 根输出，与直接写相对路径的写法保持一致），
    # 保证同一份配置每次读到的文件与顺序都稳定；某个模式没匹配到文件时保留模式本身，
    # 由 read_many 逐项回传读取错误，便于调用方发现目录写错或目录为空。
    root = resolve_root(base)
    expanded = []
    seen = set()
    for path in paths:
        text = str(path)
        if not any(char in text for char in GLOB_CHARS):
            matches = [text]
        else:
            matches = [match for match in sorted(glob.glob(resolve(text, base), recursive=True))
                       if os.path.isfile(match)]
            matches = [os.path.relpath(match, root).replace(os.sep, "/") for match in matches] or [text]
        for match in matches:
            if match in seen:
                continue
            seen.add(match)
            expanded.append(match)
    return expanded


def main():
    raw = sys.stdin.read()
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except Exception as exception:  # noqa: BLE001 - 入参不可解析时也以结果回传，避免非零退出中断整轮
        print(json.dumps({"op": "", "error": "入参 JSON 解析失败：" + str(exception)},
                         ensure_ascii=False))
        return
    op = spec.get("op", "")
    base = spec.get("base_dir") or DEFAULT_BASE
    target = resolve(spec.get("path") or "", base)
    explicit = read_encoding(spec)
    result = {"op": op, "path": target}
    try:
        if op == "write":
            directory = os.path.dirname(target)
            if directory:
                os.makedirs(directory, exist_ok=True)
            used = write_encoding(spec)
            with open(target, "w", encoding=used) as handle:
                handle.write(spec.get("content", ""))
            result["encoding"] = used
            result["size"] = os.path.getsize(target)
        elif op == "read":
            raw_bytes = _textio.read_bytes(target)
            if explicit is None and _textio.detect_encoding(raw_bytes) is None:
                result["error"] = "疑似二进制文件，无法按文本读取（如确需读取请显式指定 encoding）"
            else:
                text, used = _textio.decode(raw_bytes, explicit)
                result["content"] = text
                result["encoding"] = used
        elif op == "append":
            used = write_encoding(spec)
            with open(target, "a", encoding=used) as handle:
                handle.write(spec.get("content", ""))
            result["encoding"] = used
            result["size"] = os.path.getsize(target)
        elif op == "replace":
            text, used = _textio.read_text(target, explicit)
            find = spec.get("find", "")
            result["occurrences"] = text.count(find)
            with open(target, "w", encoding=used) as handle:
                handle.write(text.replace(find, spec.get("replace", "")))
            result["encoding"] = used
        elif op == "delete":
            result["deleted"] = os.path.exists(target)
            if result["deleted"]:
                os.remove(target)
        elif op == "exists":
            result["exists"] = os.path.exists(target)
        elif op == "list":
            result["entries"] = sorted(os.listdir(target)) if os.path.isdir(target) else []
        elif op == "scan":
            result.update(scan(spec, target))
        elif op == "read_many":
            result.update(read_many(spec, base))
        else:
            result["error"] = "unknown op: " + str(op)
    except UnicodeDecodeError as exception:  # noqa: BLE001 - 显式编码解码失败时给出可操作提示
        result["error"] = ("按 encoding=%s 解码失败：%s；"
                           "若文件为其它编码，可留空 encoding 让脚本自动识别，"
                           "或指定 gbk/utf-8-sig 等" % (explicit, exception))
    except Exception as exception:  # noqa: BLE001 - 以错误字段回传，保持协议不中断
        result["error"] = str(exception)
    print(json.dumps(result, ensure_ascii=False))


main()
