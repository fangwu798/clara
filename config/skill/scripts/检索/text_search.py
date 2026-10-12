import fnmatch
import json
import os
import re
import sys

# 工具脚本按用途分目录存放，共享辅助模块位于 scripts 根目录，故先把 scripts 根加入模块搜索路径。
SCRIPTS_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_ROOT)

import _textio
import _toolmeta

# 强制标准输入/输出为 UTF-8，避免依赖系统区域设置（中文 Windows 默认 GBK 会与 Java 侧 UTF-8 不一致）。
_textio.force_utf8_stdio()

# 工具元数据（与执行体同文件）：框架以 `python text_search.py --describe` 读取本段即得到工具声明。
_TOOL = {
    "id": "text_search",
    "name": "text_search",
    "type": "PYTHON",
    "description": "在指定目录（或单个文件）内按关键词/正则搜索文件内容，返回命中文件、行号与命中行，可按需附带上下文行。用于快速定位符号、配置项、引用关系，替代「先 scan 再逐个 read」的低效做法。入参 JSON：pattern(必填，关键词或正则)、path(可选，相对 base_dir 的文件或目录，缺省为当前分析目录)、base_dir(可选根目录，缺省为 CLARA_WORK_DIR；以 @repo 开头按仓库根解析)、regex(可选，默认 false，true 时按正则匹配)、ignore_case(可选，默认 true)、include/exclude(glob 列表，如 [\"*.java\"]、[\"*.md\"] 取反)、max_results(默认 100)、context(默认 0，命中行前后附加的行数)、max_depth(默认 8)、encoding(可选，默认自动识别文件编码，需要时可显式指定 gbk、utf-8-sig 等)、max_file_bytes(默认 2000000，超过则跳过)、max_files(默认 5000)。返回：{root, pattern, matches:[{path, line, text, context?}], count, files_scanned, truncated}。",
    "parameters": {
        "type": "object",
        "properties": {
            "pattern": {"type": "string"},
            "path": {"type": "string"},
            "base_dir": {"type": "string"},
            "regex": {"type": "boolean"},
            "ignore_case": {"type": "boolean"},
            "include": {"type": "array", "items": {"type": "string"}},
            "exclude": {"type": "array", "items": {"type": "string"}},
            "max_results": {"type": "integer"},
            "context": {"type": "integer"},
            "max_depth": {"type": "integer"},
            "encoding": {"type": "string"},
            "max_file_bytes": {"type": "integer"},
            "max_files": {"type": "integer"},
        },
        "required": ["pattern"],
    },
    "metadata": {"domain": "sample", "tags": "search,grep,python,code"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

# 仓库根：scripts 根（见文件头部 SCRIPTS_ROOT）上溯三级即仓库根。
REPO_ROOT = os.path.normpath(os.path.join(SCRIPTS_ROOT, "..", "..", ".."))

# 工作根：run.bat/run.sh 写入 CLARA_WORK_DIR（执行命令时所在目录），未设置时退回仓库根。
WORK_ROOT = os.environ.get("CLARA_WORK_DIR") or REPO_ROOT
DEFAULT_BASE = os.environ.get("CLARA_WORK_DIR") or os.path.join(REPO_ROOT, "config", "sample", "workspace")

REPO_PREFIX = "@repo"

# 搜索时默认跳过的噪声目录（与 file_ops 保持一致）。
DEFAULT_SKIP_DIRS = [
    ".git", ".idea", ".m2", "target", "node_modules", "dist", "build",
    "logs", "volumes", "__pycache__", ".clara",
]


def resolve_root(base):
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


def matches(rel_path, name, include, exclude):
    if include and not any(
            fnmatch.fnmatch(rel_path, pattern) or fnmatch.fnmatch(name, pattern)
            for pattern in include):
        return False
    if exclude and any(
            fnmatch.fnmatch(rel_path, pattern) or fnmatch.fnmatch(name, pattern)
            for pattern in exclude):
        return False
    return True


def build_predicate(pattern, regex, ignore_case):
    # 返回 (matched(text)->bool, 展示用模式)。regex=false 时按子串匹配。
    if regex:
        flags = re.IGNORECASE if ignore_case else 0
        compiled = re.compile(pattern, flags)
        return (lambda text: compiled.search(text) is not None)
    if ignore_case:
        needle = pattern.lower()
        return (lambda text: needle in text.lower())
    return (lambda text: pattern in text)


def iter_target_files(target, include, exclude, skip_dirs, max_depth, max_files):
    # 逐个产出待搜索文件；target 为文件时只产出该文件。
    if os.path.isfile(target):
        yield target
        return
    base_depth = target.rstrip(os.sep).count(os.sep)
    scanned = 0
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
            yield full
            scanned += 1
            if scanned >= max_files:
                return


def main():
    raw = sys.stdin.read()
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except Exception as exception:  # noqa: BLE001 - 入参不可解析时也以结果回传
        print(json.dumps({"pattern": "", "error": "入参 JSON 解析失败：" + str(exception)},
                         ensure_ascii=False))
        return

    pattern = spec.get("pattern") or ""
    base = spec.get("base_dir") or DEFAULT_BASE
    target = resolve(spec.get("path") or "", base)
    regex = bool(spec.get("regex"))
    ignore_case = spec.get("ignore_case")
    ignore_case = True if ignore_case is None else bool(ignore_case)
    include = spec.get("include") or []
    exclude = spec.get("exclude") or []
    skip_dirs = set(spec.get("skip_dirs") or DEFAULT_SKIP_DIRS)
    max_depth = int(spec.get("max_depth") or 8)
    max_results = int(spec.get("max_results") or 100)
    context = int(spec.get("context") or 0)
    explicit = spec.get("encoding")
    explicit = explicit.strip() if isinstance(explicit, str) and explicit.strip() else None
    max_file_bytes = int(spec.get("max_file_bytes") or 2000000)
    max_files = int(spec.get("max_files") or 5000)

    result = {"root": target, "pattern": pattern, "matches": [], "count": 0, "truncated": False}
    if not pattern:
        result["error"] = "缺少 pattern"
        print(json.dumps(result, ensure_ascii=False))
        return

    try:
        is_match = build_predicate(pattern, regex, ignore_case)
    except re.error as exception:
        result["error"] = "正则表达式非法：" + str(exception)
        print(json.dumps(result, ensure_ascii=False))
        return

    files_scanned = 0
    try:
        for full in iter_target_files(target, include, exclude, skip_dirs, max_depth, max_files):
            try:
                if os.path.getsize(full) > max_file_bytes:
                    continue
                file_bytes = _textio.read_bytes(full)
            except OSError:
                continue
            # 未显式指定编码时自动识别；识别不出（疑似二进制）则跳过该文件。
            if explicit is None and _textio.detect_encoding(file_bytes) is None:
                continue
            try:
                text, _used = _textio.decode(file_bytes, explicit)
            except UnicodeDecodeError:
                continue
            lines = text.splitlines()
            files_scanned += 1
            rel = os.path.relpath(full, target if os.path.isdir(target) else os.path.dirname(full))
            rel = rel.replace(os.sep, "/")
            for index, text in enumerate(lines):
                if not is_match(text):
                    continue
                hit = {"path": rel, "line": index + 1, "text": text.strip()[:400]}
                if context > 0:
                    start = max(0, index - context)
                    end = min(len(lines), index + context + 1)
                    hit["context"] = [lines[i][:400] for i in range(start, end)]
                result["matches"].append(hit)
                if len(result["matches"]) >= max_results:
                    result["truncated"] = True
                    break
            if result["truncated"]:
                break
    except OSError as exception:
        result["error"] = str(exception)

    result["count"] = len(result["matches"])
    result["files_scanned"] = files_scanned
    print(json.dumps(result, ensure_ascii=False))


main()
