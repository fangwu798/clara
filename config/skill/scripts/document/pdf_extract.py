"""Extract page text from a PDF file for workflow use."""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import _textio
import _toolmeta

_textio.force_utf8_stdio()
_TOOL = {
    "id": "pdf_extract", "name": "pdf_extract", "type": "PYTHON",
    "description": "Extract text from PDF pages. Input JSON: path, optional base_dir and max_pages.",
    "parameters": {"type": "object", "properties": {
        "purpose": {"type": "string"}, "path": {"type": "string"}, "base_dir": {"type": "string"},
        "max_pages": {"type": "integer"}}, "required": ["path"]},
    "metadata": {"domain": "document", "tags": "pdf,extract"}}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

def main(spec):
    path = spec.get("path")
    if not isinstance(path, str) or not path.strip():
        raise ValueError("path is required")
    base = spec.get("base_dir") or os.environ.get("CLARA_WORK_DIR") or os.getcwd()
    target = path if os.path.isabs(path) else os.path.join(base, path)
    try:
        from pypdf import PdfReader
    except ImportError:
        raise RuntimeError("pypdf is required: pip install pypdf")
    reader = PdfReader(target)
    limit = int(spec.get("max_pages") or len(reader.pages))
    pages = [{"page": index + 1, "text": reader.pages[index].extract_text() or ""}
             for index in range(min(limit, len(reader.pages)))]
    return {"path": os.path.abspath(target), "format": "pdf", "pages": pages,
            "page_count": len(reader.pages)}

if __name__ == "__main__":
    try:
        print(json.dumps(main(json.load(sys.stdin)), ensure_ascii=False))
    except Exception as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False))
        sys.exit(1)
