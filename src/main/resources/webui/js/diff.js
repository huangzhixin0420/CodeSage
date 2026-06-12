/**
 * Pure-JS line-level diff utilities.
 *
 * Uses DP LCS (O(N*M)) which is fast enough for artifact-sized texts.
 * Exports:
 *   - diffLines(oldText, newText) -> [{type:'equal'|'add'|'remove', value:string}]
 *   - createPatch(oldText, newText, {oldHeader, newHeader, context}) -> unified diff string
 *   - applyPatch(text, patchLines) -> string
 *   - computeGutter(changes) -> {oldLines,newLines}
 */

function splitLines(text) {
  if (text === null || text === undefined) return [""];
  const str = String(text);
  const lines = str.split(/\r?\n/);
  if (str.endsWith("\n")) lines.push("");
  return lines;
}

function joinLines(lines) {
  return lines.join("\n");
}

/**
 * Compute a diff between two arrays of strings.
 * Returns grouped changes where consecutive same-type hunks are merged.
 */
export function diffArrays(oldArr, newArr) {
  const N = oldArr.length;
  const M = newArr.length;
  if (N === 0 && M === 0) return [];
  if (N === 0) return [{ type: "add", value: newArr.join("\n") }];
  if (M === 0) return [{ type: "remove", value: oldArr.join("\n") }];

  // DP table of LCS lengths
  const dp = Array.from({ length: N + 1 }, () => new Array(M + 1).fill(0));
  for (let i = 1; i <= N; i++) {
    for (let j = 1; j <= M; j++) {
      if (oldArr[i - 1] === newArr[j - 1]) {
        dp[i][j] = dp[i - 1][j - 1] + 1;
      } else {
        dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
      }
    }
  }

  // Backtrack to collect individual line changes
  const changes = [];
  let i = N;
  let j = M;
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oldArr[i - 1] === newArr[j - 1]) {
      changes.push({ type: "equal", value: oldArr[i - 1] });
      i--;
      j--;
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      changes.push({ type: "add", value: newArr[j - 1] });
      j--;
    } else {
      changes.push({ type: "remove", value: oldArr[i - 1] });
      i--;
    }
  }
  changes.reverse();

  // Group consecutive same-type changes
  const grouped = [];
  let cur = changes[0];
  for (let k = 1; k < changes.length; k++) {
    if (changes[k].type === cur.type) {
      cur = { type: cur.type, value: cur.value + "\n" + changes[k].value };
    } else {
      grouped.push(cur);
      cur = changes[k];
    }
  }
  grouped.push(cur);
  return grouped;
}

export function diffLines(oldText, newText) {
  return diffArrays(splitLines(oldText), splitLines(newText));
}

function expandChanges(changes) {
  const lines = [];
  for (const ch of changes) {
    const parts = ch.value.split("\n");
    for (const p of parts) lines.push({ type: ch.type, value: p });
  }
  return lines;
}

function buildHunks(changes, context) {
  const lines = expandChanges(changes);
  const changed = [];
  for (let idx = 0; idx < lines.length; idx++) {
    if (lines[idx].type !== "equal") changed.push(idx);
  }
  if (changed.length === 0) return [];

  const hunks = [];
  let start = Math.max(0, changed[0] - context);
  let end = Math.min(lines.length - 1, changed[0] + context);

  for (let c = 1; c < changed.length; c++) {
    const idx = changed[c];
    if (idx - changed[c - 1] <= 2 * context + 1) {
      end = Math.min(lines.length - 1, idx + context);
    } else {
      hunks.push(makeHunk(lines, start, end));
      start = Math.max(0, idx - context);
      end = Math.min(lines.length - 1, idx + context);
    }
  }
  hunks.push(makeHunk(lines, start, end));
  return hunks;
}

function makeHunk(lines, start, end) {
  let oldLine = 1;
  let newLine = 1;
  for (let i = 0; i < start; i++) {
    const t = lines[i].type;
    if (t === "equal" || t === "remove") oldLine++;
    if (t === "equal" || t === "add") newLine++;
  }

  let oldCount = 0;
  let newCount = 0;
  const hunkLines = [];
  for (let i = start; i <= end; i++) {
    const ln = lines[i];
    hunkLines.push(ln);
    if (ln.type === "equal" || ln.type === "remove") oldCount++;
    if (ln.type === "equal" || ln.type === "add") newCount++;
  }

  return {
    oldStart: oldLine,
    oldCount,
    newStart: newLine,
    newCount,
    lines: hunkLines,
  };
}

export function createPatch(oldText, newText, options = {}) {
  const {
    oldHeader = "--- old\n",
    newHeader = "+++ new\n",
    context = 3,
  } = options;
  const hunks = buildHunks(diffLines(oldText, newText), context);
  if (hunks.length === 0) return "";

  let out = oldHeader + newHeader;
  for (const hunk of hunks) {
    out += `@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@\n`;
    for (const ln of hunk.lines) {
      out +=
        (ln.type === "add" ? "+" : ln.type === "remove" ? "-" : " ") +
        ln.value +
        "\n";
    }
  }
  return out;
}

export function applyPatch(text, changes) {
  const base = splitLines(text);
  const result = [];
  let oldIdx = 0;
  const lines = expandChanges(changes);
  for (const ln of lines) {
    if (ln.type === "equal") {
      result.push(base[oldIdx++]);
    } else if (ln.type === "add") {
      result.push(ln.value);
    } else if (ln.type === "remove") {
      oldIdx++;
    }
  }
  return joinLines(result);
}

export function computeGutter(changes) {
  const oldLines = [];
  const newLines = [];
  let oldNum = 1;
  let newNum = 1;
  const lines = expandChanges(changes);
  for (const ln of lines) {
    if (ln.type === "equal") {
      oldLines.push({ num: oldNum++, type: "equal" });
      newLines.push({ num: newNum++, type: "equal" });
    } else if (ln.type === "add") {
      oldLines.push({ num: "", type: "add" });
      newLines.push({ num: newNum++, type: "add" });
    } else if (ln.type === "remove") {
      oldLines.push({ num: oldNum++, type: "remove" });
      newLines.push({ num: "", type: "remove" });
    }
  }
  return { oldLines, newLines };
}
