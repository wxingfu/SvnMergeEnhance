#!/usr/bin/env bash
# SVN Enhance Merge —— 跨平台回归验证（Unix 版，与 verify-plugin.ps1 等价）
#
# 用法：
#   scripts/verify-plugin.sh --version 2025.2 [--project <path>] [--timeout 420] [--close-when-done]
#
# 存在的原因：svn4idea 的 API 不在 IDEA 平台兼容承诺内，升级 IDE 基线前必须实测。
# 手工"改 gradle.properties → runIde → 翻 idea.log"既慢又容易漏看异常，
# 这个脚本把四步固化成一条命令，退出码可接 CI。
#
# 步骤：杀旧沙箱 → 清日志 → 后台启动 runIde -PideaVersion=<版本> → 轮询插件就绪 → 扫插件异常 → PASS/FAIL
# 判定就绪的标志是日志里出现 "EnhanceMerge i18n"（本插件 EnhanceMergeBundle 首次初始化时打印），
# 它比"进程存在"更可靠：进程起来了但插件没加载完时，扫日志会漏掉启动期异常。
#
# 退出码：0 = PASS；1 = 启动器提前退出（构建/下载失败）；2 = 等待超时；3 = 日志有插件异常；64 = 参数错误
set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="2022.1"
PROJECT="${VERIFY_PROJECT:-}"
TIMEOUT=420
CLOSE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="${2:-}"; shift 2 ;;
    --project) PROJECT="${2:-}"; shift 2 ;;
    --timeout) TIMEOUT="${2:-420}"; shift 2 ;;
    --close-when-done) CLOSE=1; shift ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "[verify] unknown argument: $1" >&2; exit 64 ;;
  esac
done

if [[ -z "$VERSION" ]]; then echo "[verify] --version is required" >&2; exit 64; fi
if [[ -z "$PROJECT" ]]; then
  echo "[verify] --project not given; the sandbox will open without a project (still fine for load checks)"
fi

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
LOG_FILE="$REPO_ROOT/build/idea-sandbox/system/log/idea.log"
GRADLE_LOG="$REPO_ROOT/build/tmp/runide-$VERSION.log"
mkdir -p "$REPO_ROOT/build/tmp"

stop_sandbox() {
  if pkill -f 'idea-sandbox' 2>/dev/null; then
    echo "[verify] stopped sandbox"
  else
    echo "[verify] no sandbox running"
  fi
}

show_gradle_tail() {
  if [[ -f "$GRADLE_LOG" ]]; then
    echo "[verify] ---- tail of gradle output ----"
    tail -n 30 "$GRADLE_LOG" | sed 's/^/  | /'
  else
    echo "[verify] (no gradle output file; run './gradlew runIde -PideaVersion=$VERSION' manually to see why)"
  fi
}

# --- 1. clean slate -------------------------------------------------------
stop_sandbox
sleep 2
rm -f "$LOG_FILE"

# --- 2. launch ------------------------------------------------------------
echo "[verify] launching sandbox for IDEA $VERSION"
echo "[verify] gradle output -> $GRADLE_LOG"
(
  cd "$REPO_ROOT" || exit 1
  # 用 `sh gradlew` 而非 `./gradlew`：不依赖文件可执行位（Git Bash / 权限受限环境亦可）
  sh ./gradlew runIde "-PideaVersion=$VERSION" --args="$PROJECT" > "$GRADLE_LOG" 2>&1
) &
LAUNCHER_PID=$!

# --- 3. wait until the plugin has been loaded ------------------------------
ready=0
elapsed=0
while [[ $elapsed -lt $TIMEOUT ]]; do
  sleep 5
  elapsed=$((elapsed + 5))
  if [[ $((elapsed % 15)) -eq 0 ]]; then
    echo "[verify] waiting for the sandbox... ${elapsed}s / ${TIMEOUT}s"
  fi
  if [[ -f "$LOG_FILE" ]] && grep -q 'EnhanceMerge i18n' "$LOG_FILE" 2>/dev/null; then
    ready=1
    break
  fi
  # 启动器退出且沙箱也没起来 -> 构建/启动失败
  if ! kill -0 "$LAUNCHER_PID" 2>/dev/null && ! pgrep -f 'idea-sandbox' > /dev/null 2>&1; then
    echo "[verify] ERROR: the launcher exited before the plugin was loaded."
    show_gradle_tail
    exit 1
  fi
done

if [[ $ready -ne 1 ]]; then
  echo "[verify] TIMEOUT after ${TIMEOUT}s waiting for the plugin to load."
  show_gradle_tail
  exit 2
fi
echo "[verify] plugin loaded after ~${elapsed}s"

# --- 4. scan the startup log for plugin-related errors ---------------------
sleep 10
total=0
issues=0
if [[ -f "$LOG_FILE" ]]; then
  total=$(wc -l < "$LOG_FILE" | tr -d ' ')
  issues=$(grep -E 'SEVERE|EnhanceMerge|^[[:space:]]*(com\.svn|org\.jetbrains\.idea\.svn)' "$LOG_FILE" \
    | grep -Ev 'OSProcessHandler|MainPassesRunner|EnhanceMerge i18n' \
    | wc -l | tr -d ' ')
fi

echo ""
echo "[verify] ==================== summary ===================="
echo "[verify] target      : IDEA $VERSION"
echo "[verify] sandbox log : $LOG_FILE"
echo "[verify] log lines   : $total"
echo "[verify] issues      : $issues"

if [[ "$issues" -gt 0 ]]; then
  echo "[verify] ---------- first 20 issue lines ----------"
  grep -E 'SEVERE|EnhanceMerge|^[[:space:]]*(com\.svn|org\.jetbrains\.idea\.svn)' "$LOG_FILE" \
    | grep -Ev 'OSProcessHandler|MainPassesRunner|EnhanceMerge i18n' \
    | head -n 20 | cut -c1-180 | sed 's/^/  ! /'
  echo "[verify] RESULT: FAIL"
  if [[ $CLOSE -eq 1 ]]; then stop_sandbox; fi
  exit 3
fi

echo "[verify] RESULT: PASS (no plugin-related errors in the startup log)"
echo "[verify] manual checklist: branch popup -> revision list (status icon / checkbox click /"
echo "[verify]   speed search / Ctrl+D diff) -> Merge Selected (local changes prompt -> progress -> conflicts)"

if [[ $CLOSE -eq 1 ]]; then
  echo "[verify] closing sandbox (--close-when-done)"
  stop_sandbox
else
  echo "[verify] sandbox left running for manual verification."
fi
