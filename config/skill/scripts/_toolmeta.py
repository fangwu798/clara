"""脚本自描述辅助：让「工具定义（名称/描述/入参 Schema）」与「执行体」同处一个 .py。

框架在加载期以 `python <script> --describe` 调用脚本，脚本据此打印自身元数据 JSON，
框架解析后即得到该工具的声明；正常调用（无 --describe）时脚本仍从 stdin 读入参执行。

约定：
- 工具脚本按用途分子目录存放（如 文件操作/、检索/、文档/、命令执行/、程序编译/、数据库/、网络/）；
  框架递归扫描 scripts/ 下任意层级的 .py，目录层级不影响工具发现。
- 共享辅助模块置于 scripts 根目录并以下划线开头（如 _textio.py、_toolmeta.py）；框架跳过下划线开头的
  目录与文件（顺带排除 __pycache__），不把它们当工具。工具脚本需先把 scripts 根目录插入 sys.path，
  再 import 这些辅助模块。
- 元数据字段：id / name / type(缺省 PYTHON) / description / parameters(JSON Schema 对象) / metadata。
- script 字段由框架按脚本文件路径自动填入，无需脚本提供。
"""

import json
import sys


def describe(meta):
    """以 --describe 调用时打印元数据 JSON 并返回 True（调用方应立即 sys.exit(0)）。

    非 --describe 调用返回 False，脚本继续按正常工具调用执行。
    """
    if "--describe" in sys.argv[1:]:
        print(json.dumps(meta, ensure_ascii=False))
        return True
    return False
