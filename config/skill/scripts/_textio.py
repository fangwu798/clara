"""脚本间共享的文本 I/O 工具：统一 UTF-8 标准流 + 文本文件编码自动识别与读取。

被 config/skill/scripts 下的 file_ops.py / text_search.py / doc_outline.py 复用。
这些脚本以「独立进程 + stdin/stdout JSON」方式运行，Python 会把脚本所在目录加入
sys.path，因此可直接 `import _textio`。
"""

import codecs
import sys

DEFAULT_ENCODING = "utf-8"

# 带 BOM 的编码：先匹配 UTF-32，再匹配 UTF-8/UTF-16（UTF-32 LE 的 BOM 以 UTF-16 LE 的 BOM 为前缀）。
_BOM_ENCODINGS = (
    (codecs.BOM_UTF32_LE, "utf-32"),
    (codecs.BOM_UTF32_BE, "utf-32"),
    (codecs.BOM_UTF8, "utf-8-sig"),
    (codecs.BOM_UTF16_LE, "utf-16"),
    (codecs.BOM_UTF16_BE, "utf-16"),
)

# 无 BOM 时的候选编码，按优先级严格尝试：先 UTF-8，再中文/其它常见编码。
# 注意 1：gb18030 兼容性极好、易误判，须排在 utf-8 之后。
# 注意 2：Big5 与 gb18030 的字节范围重叠，纯字节探测无法可靠区分；因本项目以简体中文
#         （GBK/GB18030）为主，故 gb18030 优先。若遇繁体 Big5 文件，请显式传入 encoding=big5。
_CANDIDATE_ENCODINGS = ("utf-8", "gb18030", "big5", "shift_jis", "euc-kr")


def force_utf8_stdio():
    # 强制标准输入/输出为 UTF-8，避免依赖系统区域设置（中文 Windows 默认 GBK 会与 Java 侧 UTF-8 不一致）。
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):  # noqa: PERF203 - 极端环境不支持时忽略
            pass


def is_binary(raw):
    # 含 NUL 字节的内容视为二进制，不做文本读取。
    return b"\x00" in raw[:8192]


def detect_encoding(raw):
    """识别字节内容的编码；二进制或无法判定时返回 None。"""
    if not raw:
        return DEFAULT_ENCODING
    for bom, encoding in _BOM_ENCODINGS:
        if raw.startswith(bom):
            return encoding
    if is_binary(raw):
        return None
    for encoding in _CANDIDATE_ENCODINGS:
        try:
            raw.decode(encoding)
            return encoding
        except (UnicodeDecodeError, LookupError):
            continue
    return None


def read_bytes(path):
    with open(path, "rb") as handle:
        return handle.read()


def decode(raw, explicit=None):
    """把字节解码为文本，返回 (text, 实际使用的编码)。

    explicit 指定时严格按其解码（失败抛 UnicodeDecodeError）；未指定时自动识别，
    识别不出则退回 UTF-8 并替换非法字节。统一换行、去除开头 BOM。
    """
    if explicit:
        text = raw.decode(explicit)
        encoding = explicit
    else:
        encoding = detect_encoding(raw)
        if encoding is None:
            encoding = DEFAULT_ENCODING
            text = raw.decode(encoding, errors="replace")
        else:
            text = raw.decode(encoding)
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if text.startswith("\ufeff"):
        text = text[1:]
    return text, encoding


def read_text(path, explicit=None):
    """读取文件并按识别（或显式指定）的编码解码，返回 (text, 实际使用的编码)。"""
    return decode(read_bytes(path), explicit)
