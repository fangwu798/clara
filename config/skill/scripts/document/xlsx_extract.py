"""Extract worksheets and cell values from an XLSX file for workflow use."""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import _textio
import _toolmeta

_textio.force_utf8_stdio()
_TOOL = {
    "id": "xlsx_extract", "name": "xlsx_extract", "type": "PYTHON",
    "description": "Extract worksheets and cell values from an .xlsx file. Input JSON: path and optional base_dir.",
    "parameters": {"type": "object", "properties": {
        "purpose": {"type": "string"}, "path": {"type": "string"}, "base_dir": {"type": "string"}},
        "required": ["path"]},
    "metadata": {"domain": "document", "tags": "xlsx,excel,extract"}}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

def main(spec):
    path = spec.get("path")
    if not isinstance(path, str) or not path.strip():
        raise ValueError("path is required")
    base = spec.get("base_dir") or os.environ.get("CLARA_WORK_DIR") or os.getcwd()
    target = path if os.path.isabs(path) else os.path.join(base, path)
    try:
        import openpyxl
    except ImportError:
        raise RuntimeError("openpyxl is required: pip install openpyxl")
    workbook = openpyxl.load_workbook(target, data_only=False, read_only=True)
    sheets = []
    for sheet in workbook.worksheets:
        rows = [[cell.value for cell in row] for row in sheet.iter_rows()]
        sheets.append({"name": sheet.title, "rows": rows})
    workbook.close()
    return {"path": os.path.abspath(target), "format": "xlsx", "sheets": sheets}

if __name__ == "__main__":
    try:
        print(json.dumps(main(json.load(sys.stdin)), ensure_ascii=False))
    except Exception as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False))
        sys.exit(1)
