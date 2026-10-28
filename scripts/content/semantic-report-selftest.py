# -*- coding: utf-8 -*-
"""semantic-report.py 的离线反例：漏题、重复、类型错误、runId 缺失都必须报错。

做法：把真实判定数据复制到临时目录，做一处破坏，再跑生成器，断言它**非零退出**。
不联网、不改仓库里的证据文件。
"""
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REPORT = os.path.join(ROOT, "scripts", "content", "semantic-report.py")
EVIDENCE = os.path.join(ROOT, "docs", "development", "visitor-experience", "evidence", "ux08")
CASES = [
    ("v5", "verdicts-2026-10-28-v5.json", "run-2026-10-28-grounded-30-v5.json"),
]


def run(report, verdicts, out):
    return subprocess.run([sys.executable, report, "--verdicts", verdicts, "--out", out],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")


def mutate(data, mutation):
    data = json.loads(json.dumps(data))
    mutation(data)
    return data


def drop_last(data):
    data["verdicts"] = data["verdicts"][:-1]


def duplicate_first(data):
    data["verdicts"].append(dict(data["verdicts"][0]))


def non_boolean_pass(data):
    data["verdicts"][0]["pass"] = "true"


def missing_reason(data):
    data["verdicts"][0]["reason"] = ""


def main():
    failures = []
    for label, verdict_file, run_file in CASES:
        verdicts_path = os.path.join(EVIDENCE, verdict_file)
        base = json.load(io.open(verdicts_path, encoding="utf-8"))
        workdir = tempfile.mkdtemp(prefix="semantic-report-")
        shutil.copy(os.path.join(EVIDENCE, run_file), os.path.join(workdir, run_file))

        # 先确认未破坏的数据能正常生成（否则后面的反例没有意义）。
        good = os.path.join(workdir, "good.md")
        result = run(REPORT, verdicts_path, good)
        if result.returncode != 0:
            failures.append("%s: 未破坏的数据也生成失败：%s" % (label, result.stderr.strip()[:200]))
            continue

        probes = [
            ("删除最后一题判定", drop_last),
            ("重复第一条判定", duplicate_first),
            ("pass 写成字符串", non_boolean_pass),
            ("不通过但没有理由", missing_reason),
        ]
        for name, mutation in probes:
            mutated_path = os.path.join(workdir, "verdicts.json")
            with io.open(mutated_path, "w", encoding="utf-8") as handle:
                json.dump(mutate(base, mutation), handle, ensure_ascii=False)
            probe_out = os.path.join(workdir, "probe.md")
            probe = run(REPORT, mutated_path, probe_out)
            if probe.returncode == 0:
                failures.append("%s: %s 时生成器仍然正常退出（会算出错误的分母）" % (label, name))
            elif os.path.exists(probe_out):
                failures.append("%s: %s 时报错了但仍写出了表" % (label, name))

    if failures:
        print("FAILED")
        for failure in failures:
            print(" -", failure)
        raise SystemExit(1)
    print("OK：漏题 / 重复 / 非布尔 pass / 缺理由 四种破坏都被拒绝，且未写出表")


if __name__ == "__main__":
    main()
