#!/bin/bash
set -e

# CodeSage WebUI 静态资源下载脚本
# 从 cdnjs 下载指定版本的库到 src/main/resources/webui/lib/

BASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_DIR="$BASE_DIR/src/main/resources/webui/lib"
CDNJS="https://cdnjs.cloudflare.com/ajax/libs"

mkdir -p "$TARGET_DIR/languages"
mkdir -p "$TARGET_DIR/font-awesome/webfonts"

echo "Downloading to $TARGET_DIR ..."

# Highlight.js 11.9.0
echo "Downloading highlight.js..."
curl -sL "$CDNJS/highlight.js/11.9.0/highlight.min.js" -o "$TARGET_DIR/highlight.min.js"

# Highlight.js languages
for lang in kotlin java python javascript typescript go rust xml css json yaml bash sql; do
    echo "Downloading highlight.js language: $lang"
    curl -sL "$CDNJS/highlight.js/11.9.0/languages/$lang.min.js" -o "$TARGET_DIR/languages/$lang.min.js"
done

# Highlight.js themes
curl -sL "$CDNJS/highlight.js/11.9.0/styles/github-dark.min.css" -o "$TARGET_DIR/github-dark.min.css"
curl -sL "$CDNJS/highlight.js/11.9.0/styles/github.min.css" -o "$TARGET_DIR/github.min.css"

# Marked.js 9.1.6
echo "Downloading marked.js..."
curl -sL "$CDNJS/marked/9.1.6/marked.min.js" -o "$TARGET_DIR/marked.min.js"

# Font Awesome 6.5.1 CSS
echo "Downloading Font Awesome CSS..."
curl -sL "$CDNJS/font-awesome/6.5.1/css/all.min.css" -o "$TARGET_DIR/font-awesome/all.min.css"

# Font Awesome webfonts
echo "Downloading Font Awesome webfonts..."
for font in fa-brands-400.woff2 fa-regular-400.woff2 fa-solid-900.woff2 fa-v4compatibility.woff2; do
    curl -sL "$CDNJS/font-awesome/6.5.1/webfonts/$font" -o "$TARGET_DIR/font-awesome/webfonts/$font"
done

# Verify downloads
echo ""
echo "Download complete. File sizes:"
find "$TARGET_DIR" -type f -exec ls -lh {} \;

TOTAL_SIZE=$(find "$TARGET_DIR" -type f | xargs wc -c | tail -1 | awk '{print $1}')
echo ""
echo "Total size: $TOTAL_SIZE bytes"
if [ "$TOTAL_SIZE" -gt 5242880 ]; then
    echo "WARNING: Total size exceeds 5MB limit!"
    exit 1
else
    echo "OK: Total size is under 5MB limit."
fi
