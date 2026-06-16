#!/bin/bash
# PR 创建脚本 — 需要 gh auth login 后才能跑
# 用法: gh auth login --with-token < token.txt
#       bash .codesage/prs/create-prs.sh

set -e

# PR-1
gh pr create --base main --head refactor/stream-chunk-2026-06 \
  --title "refactor: 引入 StreamEvent 契约 + 3 个 Normalizer" \
  --body-file .codesage/prs/PR-1.md

# PR-2
gh pr create --base main --head refactor/stream-chunk-2026-06 \
  --title "refactor: Gateway 切到 StreamEvent + Reducer 实现" \
  --body-file .codesage/prs/PR-2.md

# PR-3
gh pr create --base main --head refactor/stream-chunk-2026-06 \
  --title "refactor: EnhancedAgentLoop 接入 Reducer + Hook 拆分 + 验证" \
  --body-file .codesage/prs/PR-3.md
