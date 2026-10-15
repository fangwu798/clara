import json
import os
import sqlite3
import sys

# 工具脚本按用途分目录存放，共享辅助模块位于 scripts 根目录，故先把 scripts 根加入模块搜索路径。
SCRIPTS_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS_ROOT)

import _textio
import _toolmeta

# 强制标准输入/输出为 UTF-8：Java 侧按 UTF-8 读写子进程管道，避免中文 Windows 区域编码（GBK）致乱码。
_textio.force_utf8_stdio()

# 工具元数据（与执行体同文件）：框架以 `python db_ops.py --describe` 读取本段即得到工具声明。
_TOOL = {
    "id": "db_ops",
    "name": "db_ops",
    "type": "PYTHON",
    "description": (
        "数据库操作（ MySQL / PostgreSQL）：查询、执行、批量执行、列表、表结构、版本信息。"
        "入参 JSON：op(query|execute|script|tables|schema|info)、db_type(sqlite|mysql|postgres，缺省 sqlite)、"
        "path(SQLite 数据库文件，相对 CLARA_WORK_DIR 或 @repo 或绝对路径)、host/port/user/password/database(非 SQLite)、"
        "sql(SQL 文本)、params(SQL 绑定参数数组；占位符 SQLite 用 ?、MySQL/PostgreSQL 用 %s)、"
        "statements(script 的语句数组，每条一句)、table(schema 的表名)、"
        "max_rows(查询返回行数上限，默认 200)、create_if_missing(SQLite 文件不存在时是否新建，默认 false)、"
        "timeout_seconds(连接/等待超时秒数，默认 30)、"
        "read_only(默认 true：仅允许 SELECT/PRAGMA/EXPLAIN/SHOW 等只读语句；写入必须显式传 read_only=false)。"
        "另需填写 purpose(请务必填写：一句话简短说明本次调用该工具的目的，会展示给用户)。"
        "默认只读，写库前请先确认目标库与语句。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "purpose": {"type": "string"},
            "op": {"type": "string","enum": ["query", "execute", "script", "tables", "schema", "info"]},
            "db_type": {"type": "string", "enum": ["sqlite", "mysql", "postgres"]},
            "path": {"type": "string"},
            "host": {"type": "string"},
            "port": {"type": ["integer", "number"]},
            "user": {"type": "string"},
            "password": {"type": "string"},
            "database": {"type": "string"},
            "sql": {"type": "string"},
            "params": {"type": "array"},
            "statements": {"type": "array", "items": {"type": "string"}},
            "table": {"type": "string"},
            "max_rows": {"type": "integer"},
            "create_if_missing": {"type": "boolean"},
            "timeout_seconds": {"type": ["integer", "number"]},
            "read_only": {"type": "boolean"},
        },
        "required": ["op"],
    },
    "metadata": {"domain": "db", "tags": "python,database,sql"},
}
if _toolmeta.describe(_TOOL):
    sys.exit(0)

# 仓库根：scripts 根（见文件头部 SCRIPTS_ROOT）上溯三级即仓库根。
REPO_ROOT = os.path.normpath(os.path.join(SCRIPTS_ROOT, "..", "..", ".."))

# 工作根：启动脚本把「用户执行命令时所在目录」写入 CLARA_WORK_DIR；相对路径都相对它解析。
WORK_ROOT = os.environ.get("CLARA_WORK_DIR") or REPO_ROOT

# 仓库根相对前缀：以 @repo 开头时按仓库根解析，与「当前分析目录」（CLARA_WORK_DIR）区分开。
REPO_PREFIX = "@repo"

DEFAULT_MAX_ROWS = 200
DEFAULT_TIMEOUT_SECONDS = 30.0

# 单个单元格的字符上限，避免大字段把上下文挤满。
CELL_MAX_CHARS = 4000

# 只读语句前缀：read_only 为 true（默认）时仅放行这些开头的语句。
READ_ONLY_PREFIXES = ("select", "pragma", "explain", "with", "show", "describe", "desc", "values")


def resolve_path(path):
    # path 可为 "@repo" 前缀、绝对路径或相对工作根（CLARA_WORK_DIR）的路径。
    if path.startswith(REPO_PREFIX):
        rest = path[len(REPO_PREFIX):].lstrip("/\\")
        return os.path.join(REPO_ROOT, rest) if rest else REPO_ROOT
    return path if os.path.isabs(path) else os.path.join(WORK_ROOT, path)


def is_writable(spec):
    # read_only 缺省为 true（只读）；显式传 false / "false" / 0 / no 才允许写入。
    value = spec.get("read_only")
    if value is False:
        return True
    if isinstance(value, str) and value.strip().lower() in ("false", "0", "no"):
        return True
    if isinstance(value, (int, float)) and value == 0:
        return True
    return False


def require_writable(spec, db_type):
    if not is_writable(spec):
        raise PermissionError("当前为只读模式（read_only 默认 true）：如需写入 %s，请显式传 read_only=false" % db_type)


def connect(spec):
    # 按 db_type 建立连接并返回 (connection, db_type)；非 SQLite 驱动缺失时给出安装提示。
    db_type = (spec.get("db_type") or "sqlite").strip().lower()
    timeout = float(spec.get("timeout_seconds") or DEFAULT_TIMEOUT_SECONDS)
    if db_type == "sqlite":
        path = spec.get("path")
        if not path:
            raise ValueError("SQLite 需要 path（数据库文件路径，相对 CLARA_WORK_DIR 或 @repo 或绝对路径）")
        target = resolve_path(str(path))
        if not os.path.exists(target) and not spec.get("create_if_missing"):
            raise FileNotFoundError("SQLite 文件不存在：" + target + "（如需新建请传 create_if_missing=true）")
        return sqlite3.connect(target, timeout=timeout), "sqlite", target
    if db_type == "mysql":
        try:
            import pymysql
        except ImportError:
            raise RuntimeError("未安装 pymysql，无法连接 MySQL（pip install pymysql）")
        connection = pymysql.connect(
            host=spec.get("host") or "127.0.0.1",
            port=int(spec.get("port") or 3306),
            user=spec.get("user"),
            password=spec.get("password") or "",
            database=spec.get("database"),
            charset="utf8mb4",
            connect_timeout=int(timeout),
        )
        return connection, "mysql", "%s:%s/%s" % (spec.get("host") or "127.0.0.1",
                                                  spec.get("port") or 3306, spec.get("database") or "")
    if db_type == "postgres":
        try:
            import psycopg2
        except ImportError:
            raise RuntimeError("未安装 psycopg2，无法连接 PostgreSQL（pip install psycopg2-binary）")
        connection = psycopg2.connect(
            host=spec.get("host") or "127.0.0.1",
            port=int(spec.get("port") or 5432),
            user=spec.get("user"),
            password=spec.get("password") or "",
            dbname=spec.get("database"),
            connect_timeout=int(timeout),
        )
        return connection, "postgres", "%s:%s/%s" % (spec.get("host") or "127.0.0.1",
                                                     spec.get("port") or 5432, spec.get("database") or "")
    raise ValueError("不支持的 db_type：" + db_type)


def bindings(spec):
    # SQL 绑定参数：数组按顺序绑定；对象/数组值转为 JSON 文本，其余原样交驱动处理。
    values = spec.get("params")
    if values is None:
        return ()
    if isinstance(values, dict):
        return values
    if not isinstance(values, list):
        values = [values]
    return tuple(json.dumps(value, ensure_ascii=False) if isinstance(value, (dict, list)) else value
                 for value in values)


def require_sql(spec):
    sql = spec.get("sql")
    sql = sql.strip() if isinstance(sql, str) else ""
    if not sql:
        raise ValueError("需要提供 sql（SQL 文本）")
    return sql


def cell(value):
    # 把驱动返回的值转成可 JSON 序列化的形式，并把超长文本截断。
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, (bytes, bytearray)):
        return value.decode("utf-8", errors="replace")
    text = str(value)
    return text if len(text) <= CELL_MAX_CHARS else text[:CELL_MAX_CHARS] + "...(截断)"


def fetch_rows(cursor, spec):
    # 查询结果：列名 + 行对象数组，按 max_rows 上限截断（多取一行用于判断是否还有更多）。
    max_rows = int(spec.get("max_rows") or DEFAULT_MAX_ROWS)
    columns = [item[0] for item in (cursor.description or [])]
    fetched = cursor.fetchmany(max_rows + 1)
    truncated = len(fetched) > max_rows
    rows = [{name: cell(value) for name, value in zip(columns, row)}
            for row in fetched[:max_rows]]
    return {"columns": columns, "rows": rows, "count": len(rows), "truncated": truncated}


def op_query(cursor, spec):
    cursor.execute(require_sql(spec), bindings(spec))
    return fetch_rows(cursor, spec)


def op_execute(cursor, spec):
    cursor.execute(require_sql(spec), bindings(spec))
    return {"rowcount": cursor.rowcount}


def op_script(cursor, spec):
    # 批量执行：statements 为语句数组，逐条执行，任一条失败即中断并回传已执行条数。
    statements = spec.get("statements")
    if not isinstance(statements, list) or not statements:
        raise ValueError("script 需要 statements（语句数组，每条一句）")
    executed = []
    try:
        for statement in statements:
            text = statement.strip() if isinstance(statement, str) else ""
            if not text:
                continue
            cursor.execute(text, bindings(spec) if len(statements) == 1 else ())
            executed.append({"sql": text.split(None, 1)[0], "rowcount": cursor.rowcount})
    except Exception as exception:  # noqa: BLE001 - 回传已执行部分，便于判断是否需回滚
        raise RuntimeError("批量执行在第 %d 条失败：%s（已执行 %d 条）"
                           % (len(executed) + 1, exception, len(executed)))
    return {"statements": executed, "count": len(executed)}


def op_tables(cursor, db_type):
    if db_type == "sqlite":
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
    elif db_type == "mysql":
        cursor.execute("SELECT table_name FROM information_schema.tables "
                       "WHERE table_schema = DATABASE() ORDER BY table_name")
    else:
        cursor.execute("SELECT table_name FROM information_schema.tables "
                       "WHERE table_schema = 'public' ORDER BY table_name")
    return {"tables": [row[0] for row in cursor.fetchall()]}


def op_schema(cursor, db_type, spec):
    table = spec.get("table")
    table = table.strip() if isinstance(table, str) else ""
    if not table:
        raise ValueError("schema 需要 table（表名）")
    if db_type == "sqlite":
        if not table.replace("_", "").isalnum():
            raise ValueError("表名仅支持字母、数字与下划线：" + table)
        cursor.execute("PRAGMA table_info(" + table + ")")
        return {"table": table, "columns": [
            {"name": row[1], "type": row[2], "nullable": not row[3],
             "default": cell(row[4]), "primary_key": bool(row[5])}
            for row in cursor.fetchall()]}
    if db_type == "mysql":
        cursor.execute("SELECT column_name, data_type, is_nullable, column_key, column_default "
                       "FROM information_schema.columns "
                       "WHERE table_schema = DATABASE() AND table_name = %s "
                       "ORDER BY ordinal_position", (table,))
        return {"table": table, "columns": [
            {"name": row[0], "type": row[1], "nullable": row[2] == "YES",
             "key": cell(row[3]), "default": cell(row[4])}
            for row in cursor.fetchall()]}
    cursor.execute("SELECT column_name, data_type, is_nullable, column_default "
                   "FROM information_schema.columns "
                   "WHERE table_schema = 'public' AND table_name = %s "
                   "ORDER BY ordinal_position", (table,))
    return {"table": table, "columns": [
        {"name": row[0], "type": row[1], "nullable": row[2] == "YES", "default": cell(row[3])}
        for row in cursor.fetchall()]}


def op_info(cursor, db_type):
    if db_type == "sqlite":
        cursor.execute("SELECT sqlite_version()")
        return {"db_type": db_type, "version": cursor.fetchone()[0]}
    if db_type == "mysql":
        cursor.execute("SELECT VERSION()")
        return {"db_type": db_type, "version": cursor.fetchone()[0]}
    cursor.execute("SHOW server_version")
    return {"db_type": db_type, "version": cursor.fetchone()[0]}


def main():
    raw = sys.stdin.read()
    try:
        spec = json.loads(raw) if raw and raw.strip() else {}
        if not isinstance(spec, dict):
            raise ValueError("入参必须是 JSON 对象")
    except Exception as exception:  # noqa: BLE001 - 入参不可解析时也以结果回传，避免非零退出中断整轮
        print(json.dumps({"op": "", "error": "入参 JSON 解析失败：" + str(exception)}, ensure_ascii=False))
        return
    op = (spec.get("op") or "").strip()
    result = {"op": op}
    connection = None
    try:
        connection, db_type, target = connect(spec)
        result["db_type"] = db_type
        result["target"] = target
        cursor = connection.cursor()
        if op == "query":
            result["result"] = op_query(cursor, spec)
        elif op == "execute":
            require_writable(spec, db_type)
            result["result"] = op_execute(cursor, spec)
            connection.commit()
            result["committed"] = True
        elif op == "script":
            require_writable(spec, db_type)
            result["result"] = op_script(cursor, spec)
            connection.commit()
            result["committed"] = True
        elif op == "tables":
            result["result"] = op_tables(cursor, db_type)
        elif op == "schema":
            result["result"] = op_schema(cursor, db_type, spec)
        elif op == "info":
            result["result"] = op_info(cursor, db_type)
        else:
            result["error"] = "unknown op: " + str(op)
    except Exception as exception:  # noqa: BLE001 - 以错误字段回传，保持协议不中断
        result["error"] = str(exception)
    finally:
        if connection is not None:
            try:
                connection.close()
            except Exception:  # noqa: BLE001 - 关闭失败不影响已回传结果
                pass
    print(json.dumps(result, ensure_ascii=False))


main()
