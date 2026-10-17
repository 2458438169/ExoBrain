"""ExoBrain 检索评测（开发任务 02）共用工具。

只使用 Python 标准库，兼容 Python 3.7+（CI 使用 3.11 也能运行）。
不读取、不打印任何密钥：密钥只通过环境变量名交给 HTTP 客户端。
"""

import datetime
import hashlib
import io
import json
import os
import re
import sys


class EvalError(Exception):
    """评测脚本的可预期错误：配置、语料、数据集、外部服务。"""


# --------------------------------------------------------------------------
# 路径
# --------------------------------------------------------------------------

def scripts_dir():
    return os.path.dirname(os.path.abspath(__file__))


def eval_root():
    return os.path.dirname(scripts_dir())


def repo_root():
    return os.path.dirname(eval_root())


def evaluation_path(*parts):
    return os.path.join(eval_root(), *parts)


def repo_path(*parts):
    return os.path.join(repo_root(), *parts)


def ensure_dir(path):
    if path and not os.path.isdir(path):
        os.makedirs(path)
    return path


# --------------------------------------------------------------------------
# 哈希与文本标准化
# --------------------------------------------------------------------------

def sha256_bytes(data):
    digest = hashlib.sha256()
    digest.update(data)
    return digest.hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while True:
            block = handle.read(65536)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def sha256_text(text):
    return sha256_bytes(text.encode("utf-8"))


def normalize_newlines(text):
    """移除首个可选 BOM，把 CRLF / CR 统一为 LF（与 contracts.md 第 3 节一致）。"""
    if text.startswith("\ufeff"):
        text = text[1:]
    return text.replace("\r\n", "\n").replace("\r", "\n")


def read_text_normalized(path):
    with io.open(path, "rb") as handle:
        raw = handle.read()
    return normalize_newlines(raw.decode("utf-8"))


_WS_RE = re.compile(r"\s+")


def norm_ws(text):
    """用于事实字符串比对：所有空白折叠为单个空格并去首尾。"""
    if text is None:
        return ""
    return _WS_RE.sub(" ", text).strip()


def norm_ws_lower(text):
    return norm_ws(text).lower()


# --------------------------------------------------------------------------
# JSON / JSONL
# --------------------------------------------------------------------------

def load_json(path):
    try:
        with io.open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (IOError, OSError) as exc:
        raise EvalError("无法读取 JSON 文件 %s: %s" % (path, exc))
    except ValueError as exc:
        raise EvalError("JSON 解析失败 %s: %s" % (path, exc))


def dump_json_text(obj):
    return json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def write_json(path, obj):
    ensure_dir(os.path.dirname(os.path.abspath(path)))
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(dump_json_text(obj))


def load_jsonl(path):
    rows = []
    try:
        with io.open(path, "r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, start=1):
                text = line.strip()
                if not text:
                    continue
                try:
                    rows.append(json.loads(text))
                except ValueError as exc:
                    raise EvalError("JSONL 第 %d 行解析失败 %s: %s" % (line_no, path, exc))
    except (IOError, OSError) as exc:
        raise EvalError("无法读取 JSONL 文件 %s: %s" % (path, exc))
    return rows


def write_jsonl(path, rows):
    ensure_dir(os.path.dirname(os.path.abspath(path)))
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=False))
            handle.write("\n")


def append_jsonl(path, row):
    ensure_dir(os.path.dirname(os.path.abspath(path)))
    with io.open(path, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(row, ensure_ascii=False, sort_keys=False))
        handle.write("\n")


# --------------------------------------------------------------------------
# 时间与统计
# --------------------------------------------------------------------------

def utc_now_iso():
    return datetime.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def percentile(values, quantile):
    """线性插值分位数（numpy 'linear' 口径）。

    小样本说明：样本量小于 20 时 p95 只是"接近最大值的插值点"，
    报告必须带样本量，不能当作稳定延迟指标。
    """
    data = sorted(float(v) for v in values if v is not None)
    if not data:
        return None
    if len(data) == 1:
        return data[0]
    if quantile <= 0:
        return data[0]
    if quantile >= 1:
        return data[-1]
    position = (len(data) - 1) * float(quantile)
    lower = int(position)
    upper = min(lower + 1, len(data) - 1)
    weight = position - lower
    return data[lower] * (1.0 - weight) + data[upper] * weight


def latency_stats(values):
    data = [float(v) for v in values if v is not None]
    if not data:
        return {"n": 0, "p50": None, "p95": None}
    return {
        "n": len(data),
        "min": min(data),
        "p50": percentile(data, 0.5),
        "p95": percentile(data, 0.95),
        "max": max(data),
        "mean": sum(data) / float(len(data)),
    }


def round_floats(obj, digits=4):
    """递归地把浮点数四舍五入，便于阅读与比较（不改变整数/布尔/字符串）。"""
    if isinstance(obj, float):
        return round(obj, digits)
    if isinstance(obj, dict):
        return dict((key, round_floats(value, digits)) for key, value in obj.items())
    if isinstance(obj, list):
        return [round_floats(value, digits) for value in obj]
    return obj


# --------------------------------------------------------------------------
# 杂项
# --------------------------------------------------------------------------

def sql_literal(text):
    """把文本转成 SQL 字符串字面量。

    调用方保证连接使用 standard_conforming_strings=on，因此只需把单引号写成两个。
    """
    if text is None:
        return "NULL"
    if "\x00" in text:
        raise EvalError("文本包含 NUL 字节，无法写入 SQL 字面量")
    return "'" + text.replace("'", "''") + "'"


def vector_literal(values):
    """pgvector 字面量，例如 '[0.1,-0.2]'。拒绝 NaN / Inf。"""
    parts = []
    for value in values:
        number = float(value)
        if number != number or number in (float("inf"), float("-inf")):
            raise EvalError("向量包含非有限数值，拒绝写入 pgvector")
        parts.append(repr(number))
    return "[" + ",".join(parts) + "]"


def print_json(obj):
    sys.stdout.write(dump_json_text(round_floats(obj, 6)))


def short_hash(text, length=12):
    return sha256_text(text)[:length]


def canonical_json(obj):
    """稳定序列化：用于计算 indexRevision / 冻结哈希。"""
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def relpath(path):
    """相对仓库根路径（用于报告），不在仓库内时返回绝对路径。"""
    try:
        absolute = os.path.abspath(path)
        root = os.path.abspath(repo_root())
        if absolute.lower().startswith(root.lower() + os.sep):
            return os.path.relpath(absolute, root).replace("\\", "/")
    except Exception:  # pragma: no cover - 仅用于报告
        pass
    return os.path.abspath(path)
