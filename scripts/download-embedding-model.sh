#!/usr/bin/env bash
set -euo pipefail

# 6.3.3 下载本地 embedding 模型脚本
# 默认下载 sentence-transformers/all-MiniLM-L6-v2 的 ONNX 模型与 tokenizer 到用户级缓存目录。
# 来源：HuggingFace 仓库镜像（Xenova 提供已转换的 ONNX 模型）。

MODEL_DIR="${CODESAGE_MODEL_DIR:-$HOME/.codesage/models/all-MiniLM-L6-v2}"
MODEL_URL="https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/model.onnx"
TOKENIZER_URL="https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/tokenizer.json"

mkdir -p "$MODEL_DIR"

echo "Downloading embedding model to $MODEL_DIR ..."

if command -v curl >/dev/null 2>&1; then
    curl -L --fail "$MODEL_URL" -o "$MODEL_DIR/model.onnx"
    curl -L --fail "$TOKENIZER_URL" -o "$MODEL_DIR/tokenizer.json"
elif command -v wget >/dev/null 2>&1; then
    wget -q --show-progress "$MODEL_URL" -O "$MODEL_DIR/model.onnx"
    wget -q --show-progress "$TOKENIZER_URL" -O "$MODEL_DIR/tokenizer.json"
else
    echo "Error: curl or wget is required to download the model." >&2
    exit 1
fi

echo "Embedding model downloaded successfully."
echo "  Model:      $MODEL_DIR/model.onnx"
echo "  Tokenizer:  $MODEL_DIR/tokenizer.json"
