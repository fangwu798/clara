import json
import os
import sys

# 仓库根：本脚本位于 <repo>/config/skill/scripts/file_ops.py，故上溯三级即仓库根。
HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))

# 默认工作区：<repo>/config/sample/workspace。
DEFAULT_BASE = os.path.join(REPO_ROOT, "config", "sample", "workspace")


def resolve(path, base):
    # base 可为绝对路径或相对仓库根的路径；path 可为绝对路径或相对 base 的路径。
    root = base if os.path.isabs(base) else os.path.join(REPO_ROOT, base)
    if not path:
        return root
    return path if os.path.isabs(path) else os.path.join(root, path)


def main():
    spec = json.load(sys.stdin)
    op = spec.get("op", "")
    base = spec.get("base_dir") or DEFAULT_BASE
    target = resolve(spec.get("path") or "", base)
    result = {"op": op, "path": target}
    try:
        if op == "write":
            directory = os.path.dirname(target)
            if directory:
                os.makedirs(directory, exist_ok=True)
            with open(target, "w", encoding="utf-8") as handle:
                handle.write(spec.get("content", ""))
            result["size"] = os.path.getsize(target)
        elif op == "read":
            with open(target, "r", encoding="utf-8") as handle:
                result["content"] = handle.read()
        elif op == "append":
            with open(target, "a", encoding="utf-8") as handle:
                handle.write(spec.get("content", ""))
            result["size"] = os.path.getsize(target)
        elif op == "replace":
            with open(target, "r", encoding="utf-8") as handle:
                text = handle.read()
            find = spec.get("find", "")
            result["occurrences"] = text.count(find)
            with open(target, "w", encoding="utf-8") as handle:
                handle.write(text.replace(find, spec.get("replace", "")))
        elif op == "delete":
            result["deleted"] = os.path.exists(target)
            if result["deleted"]:
                os.remove(target)
        elif op == "exists":
            result["exists"] = os.path.exists(target)
        elif op == "list":
            result["entries"] = sorted(os.listdir(target)) if os.path.isdir(target) else []
        else:
            result["error"] = "unknown op: " + str(op)
    except Exception as exception:  # noqa: BLE001 - 以错误字段回传，保持协议不中断
        result["error"] = str(exception)
    print(json.dumps(result, ensure_ascii=False))


main()
