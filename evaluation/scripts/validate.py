"""命令行校验器：题集 + 语料快照 + 冻结哈希。

用法（在仓库根目录执行）：
  python evaluation/scripts/validate.py --dataset evaluation/datasets/questions-v1.jsonl
  python evaluation/scripts/validate.py --dataset evaluation/datasets/questions-v1.jsonl --json
  python evaluation/scripts/validate.py --dataset evaluation/datasets/questions-v1.jsonl --emit-freeze

退出码：0 通过；2 校验失败（重复 ID、失效行号、hash 漂移、缺少 gold、split 重叠等）；
        3 参数/环境错误（文件不存在）。
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from exo_common import EvalError, print_json, relpath, write_json, sha256_file
from exo_corpus import CorpusSnapshot
from exo_dataset import (
    dataset_stats,
    dev_split_fingerprint,
    group_split_table,
    load_questions,
    test_split_fingerprint,
    validate_questions,
)

DEFAULT_DATASET = os.path.join("evaluation", "datasets", "questions-v1.jsonl")
DEFAULT_MANIFEST = os.path.join("evaluation", "corpus", "v1-2026-10-14", "manifest.json")
DEFAULT_FREEZE = os.path.join("evaluation", "datasets", "freeze.json")


def parse_args(argv):
    parser = argparse.ArgumentParser(description="校验 ExoBrain 评测题集与语料快照")
    parser.add_argument("--dataset", default=DEFAULT_DATASET, help="题集 JSONL 路径")
    parser.add_argument("--corpus-manifest", default=DEFAULT_MANIFEST, help="语料 manifest 路径")
    parser.add_argument("--freeze", default=None, help="冻结记录 JSON；传入后校验题集与测试集哈希")
    parser.add_argument("--emit-freeze", action="store_true", help="把当前哈希写入冻结记录（调参前执行一次）")
    parser.add_argument("--freeze-out", default=DEFAULT_FREEZE, help="--emit-freeze 的输出路径")
    parser.add_argument("--json", action="store_true", help="以 JSON 输出统计信息")
    parser.add_argument("--no-corpus", action="store_true", help="只校验题集结构，不加载语料（离线快速检查）")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    if not os.path.isfile(args.dataset):
        sys.stderr.write("题集文件不存在: %s\n" % args.dataset)
        return 3
    snapshot = None
    if not args.no_corpus:
        if not os.path.isfile(args.corpus_manifest):
            sys.stderr.write("语料 manifest 不存在: %s\n" % args.corpus_manifest)
            return 3
        try:
            snapshot = CorpusSnapshot(args.corpus_manifest)
        except EvalError as exc:
            sys.stderr.write(str(exc) + "\n")
            return 2
        try:
            snapshot.verify()
        except EvalError as exc:
            sys.stderr.write(str(exc) + "\n")
            return 2

    try:
        questions = load_questions(args.dataset)
    except EvalError as exc:
        sys.stderr.write(str(exc) + "\n")
        return 2

    freeze = None
    freeze_path = args.freeze
    if freeze_path and os.path.isfile(freeze_path):
        from exo_common import load_json

        freeze = load_json(freeze_path)
    elif freeze_path:
        sys.stderr.write("冻结记录不存在: %s（可先用 --emit-freeze 生成）\n" % freeze_path)
        return 3

    problems = validate_questions(
        questions, snapshot, dataset_path=os.path.abspath(args.dataset), freeze=freeze
    )
    stats = dataset_stats(questions)
    stats["datasetPath"] = relpath(args.dataset)
    stats["datasetSha256"] = sha256_file(args.dataset)
    stats["testSplitSha256"] = test_split_fingerprint(questions)
    stats["devSplitSha256"] = dev_split_fingerprint(questions)
    stats["groups"] = group_split_table(questions)
    if snapshot is not None:
        stats["corpusVersion"] = snapshot.version
        stats["corpusManifestSha256"] = snapshot.manifest_sha256
        stats["corpusDocumentSha256"] = snapshot.document_sha256()
        stats["sectionCounts"] = [
            {
                "docKey": document["docKey"],
                "role": document["role"],
                "sections": len(snapshot.sections(document["docKey"])),
                "lines": snapshot.line_count(document["docKey"]),
            }
            for document in snapshot.documents
        ]
        stats["sectionCountTotal"] = snapshot.section_count()
    stats["problems"] = problems
    stats["ok"] = not problems

    if args.emit_freeze:
        payload = {
            "schemaVersion": 1,
            "note": "冻结记录：数据集与保留测试集在调参前固定；改动后必须重新生成并重跑基线。",
            "datasetPath": relpath(args.dataset),
            "datasetSha256": stats["datasetSha256"],
            "testSplitSha256": stats["testSplitSha256"],
            "devSplitSha256": stats["devSplitSha256"],
            "corpusManifestSha256": stats.get("corpusManifestSha256"),
            "corpusDocumentSha256": stats.get("corpusDocumentSha256"),
            "questionCount": stats["total"],
            "answerable": stats["answerable"],
            "unanswerable": stats["unanswerable"],
        }
        write_json(args.freeze_out, payload)
        sys.stderr.write("已写入冻结记录: %s\n" % args.freeze_out)

    if args.json:
        print_json(stats)
    else:
        sys.stdout.write("题集: %s\n" % stats["datasetPath"])
        sys.stdout.write("题数: %d（有答案 %d / 资料不足 %d）\n" % (stats["total"], stats["answerable"], stats["unanswerable"]))
        sys.stdout.write("split: %s\n" % stats["bySplit"])
        sys.stdout.write("类别: %s\n" % stats["byCategory"])
        sys.stdout.write("数据集 SHA-256: %s\n" % stats["datasetSha256"])
        sys.stdout.write("测试集 SHA-256: %s\n" % stats["testSplitSha256"])
        if snapshot is not None:
            sys.stdout.write("语料: %s（manifest %s）\n" % (stats["corpusVersion"], stats["corpusManifestSha256"]))
            sys.stdout.write("section 数: %d %s\n" % (stats["sectionCountTotal"], stats["sectionCounts"]))
        if problems:
            sys.stdout.write("校验失败 %d 项:\n" % len(problems))
            for problem in problems:
                sys.stdout.write("  - %s\n" % problem)
        else:
            sys.stdout.write("校验通过：题集、证据行号、源 hash、split 切分、冻结哈希一致\n")
    return 0 if not problems else 2


if __name__ == "__main__":
    sys.exit(main())