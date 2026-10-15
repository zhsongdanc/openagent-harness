#!/usr/bin/env bash
# 用 openagent 适配器跑 Terminal-Bench 任务。
# 用法：
#   ./run_openagent.sh                       # 默认跑 largest-eigenval 单题冒烟
#   ./run_openagent.sh largest-eigenval      # 跑指定任务目录
#   ./run_openagent.sh --full                # 跑整个数据集（89 题，慎用/烧 token）
set -euo pipefail

HERE="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATH="$HOME/.local/bin:$PATH"
export PYTHONPATH="$HERE"

MODEL="${OPENAGENT_MODEL:-deepseek/deepseek-chat}"

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "[run_openagent] 警告：DEEPSEEK_API_KEY 未设置，容器内 openagent 将无法调用模型。" >&2
fi

if [ "${1:-}" = "--full" ]; then
  echo "[run_openagent] 全量 terminal-bench@2.0（本地 clone 目录）"
  exec harbor run \
    --path "$HERE/terminal-bench-2" \
    --agent openagent_adapter.openagent:OpenAgent \
    --model "$MODEL" \
    --n-concurrent 4
fi

TASK="${1:-largest-eigenval}"
echo "[run_openagent] 单题冒烟: $TASK"
exec harbor run \
  --path "$HERE/terminal-bench-2/$TASK" \
  --agent openagent_adapter.openagent:OpenAgent \
  --model "$MODEL"
