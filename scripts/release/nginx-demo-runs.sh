#!/usr/bin/env bash
# 把「游客读取运行 / 取消自己的运行」这几条精确路径补进线上 Nginx，其它转发、认证、限流设置保持不变。
#
#   ./scripts/release/nginx-demo-runs.sh --check             # 只打印差异，不改文件
#   ./scripts/release/nginx-demo-runs.sh --apply             # 备份 → 语法检查 → 写入
#   ./scripts/release/nginx-demo-runs.sh --apply --reload    # 另外 reload（默认不动线上进程）
#
# 规则来源是仓库模板 deploy/nginx.conf：脚本从模板里取出「取消方法 map」「405 守卫」「公开路径白名单」
# 三段，不另写副本。重复执行是幂等的；语法检查失败时线上文件保持原样。
set -euo pipefail

SITE=${SITE:-/etc/nginx/sites-available/exobrain}
TEMPLATE=${TEMPLATE:-$(cd "$(dirname "$0")/../.." && pwd)/deploy/nginx.conf}
BACKUP_DIR=${BACKUP_DIR:-/opt/exobrain/backups}
MODE="--check"
RELOAD=0
for arg in "$@"; do
    case "$arg" in
        --check) MODE="--check" ;;
        --apply) MODE="--apply" ;;
        --reload) RELOAD=1 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

[ -r "$SITE" ] || { echo "site config not readable: $SITE" >&2; exit 2; }
[ -r "$TEMPLATE" ] || { echo "template not readable: $TEMPLATE" >&2; exit 2; }

STAMP=$(date +%Y%m%d-%H%M%S)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
CANDIDATE="$WORK/site.conf"

python3 - "$SITE" "$TEMPLATE" "$CANDIDATE" <<'PY'
import re
import sys

site_path, template_path, out_path = sys.argv[1:4]
site = open(site_path, encoding="utf-8").read()
template = open(template_path, encoding="utf-8").read()

def must(pattern, text, what):
    found = re.search(pattern, text, re.MULTILINE | re.DOTALL)
    if not found:
        sys.exit("cannot extract %s from the repository template" % what)
    return found.group(0)

map_block = must(r'^# 游客取消.*?^\}\n', template, "the cancel-method map")
guard = must(r'^        if \(\$exobrain_demo_cancel_method = 1\) \{.*?^\s*\}\n', template, "the 405 guard")
allow = must(r'^        if \(\$uri !~ "\^/api/demo/.*?\) \{\n', template, "the demo allowlist")

# 1) Replace an older map too; reapplying a corrected template must converge.
site, maps_replaced = re.subn(
    r'^(?:# 游客取消[^\n]*\n)?map[^\n]*\$exobrain_demo_cancel_method \{.*?^\}\n',
    map_block, site, count=1, flags=re.MULTILINE | re.DOTALL)
if not maps_replaced:
    marker = re.search(r"^server \{", site, re.MULTILINE)
    if not marker:
        sys.exit("cannot find the server block")
    site = site[:marker.start()] + map_block + "\n" + site[marker.start():]

# 2) 公开路径白名单：整行替换成模板里的同一行。
site, replaced = re.subn(r'^        if \(\$uri !~ "\^/api/demo/.*?\) \{\n', allow, site,
                         count=1, flags=re.MULTILINE)
if replaced != 1:
    sys.exit("cannot replace the demo allowlist line")

# 3) 方法守卫：插在白名单 if 之前。
if "exobrain_demo_cancel_method = 1" not in site:
    site = site.replace(allow, guard + allow, 1)

open(out_path, "w", encoding="utf-8").write(site)
PY

if diff -u "$SITE" "$CANDIDATE" > "$WORK/site.diff"; then
    echo "already up to date: $SITE"
    exit 0
fi

echo "--- diff against $SITE ---"
cat "$WORK/site.diff"

if [ "$MODE" = "--check" ]; then
    echo "dry run: rerun with --apply to install"
    exit 0
fi

# 在独立的最小配置里验证候选片段（站点文件是 http 级片段，不能单独 nginx -t）。
{
    echo "worker_processes 1;"
    echo "error_log stderr;"
    echo "events { worker_connections 64; }"
    echo "http {"
    echo "    include /etc/nginx/mime.types;"
    echo "    access_log off;"
    cat "$CANDIDATE"
    echo "}"
} > "$WORK/nginx.conf"
if ! nginx -t -c "$WORK/nginx.conf" -p /etc/nginx > "$WORK/t.out" 2>&1; then
    echo "candidate config failed nginx -t, nothing was written:" >&2
    cat "$WORK/t.out" >&2
    exit 1
fi
echo "candidate syntax: $(tail -n 1 "$WORK/t.out")"

mkdir -p "$BACKUP_DIR"
BACKUP="$BACKUP_DIR/nginx-$(basename "$SITE")-$STAMP"
cp -p "$SITE" "$BACKUP"
echo "backup: $BACKUP"

install -m 0644 "$CANDIDATE" "$SITE"
if ! nginx -t > "$WORK/live.out" 2>&1; then
    cp -p "$BACKUP" "$SITE"
    echo "live nginx -t failed after writing, restored from backup:" >&2
    cat "$WORK/live.out" >&2
    exit 1
fi
echo "installed: $SITE"

if [ "$RELOAD" = "1" ]; then
    if ! systemctl reload nginx; then
        cp -p "$BACKUP" "$SITE"
        nginx -t && systemctl reload nginx
        echo "reload failed; restored the previous site configuration" >&2
        exit 1
    fi
    echo "reloaded nginx"
fi
echo "rollback: cp -p $BACKUP $SITE && nginx -t && systemctl reload nginx"
