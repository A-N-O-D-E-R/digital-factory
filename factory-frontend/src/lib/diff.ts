/** One row of a side-by-side diff: what the line was (left) and is (right); null where there is none. */
export type Side = { number: number; text: string; kind: "context" | "removed" | "added" };
export type Row = { left: Side | null; right: Side | null } | { hunk: string };
export type FileDiff = { path: string; rows: Row[] };

/**
 * A unified diff (git's) as side-by-side rows: removed and added lines of a change face each other,
 * context lines are on both sides.
 */
export function splitDiff(patch: string): FileDiff[] {
  const files: FileDiff[] = [];
  let file: FileDiff | undefined;
  let left = 0;
  let right = 0;
  let removed: Side[] = [];
  let added: Side[] = [];
  const flush = () => {
    for (let i = 0; i < Math.max(removed.length, added.length); i++) {
      file!.rows.push({ left: removed[i] ?? null, right: added[i] ?? null });
    }
    removed = [];
    added = [];
  };
  for (const line of patch.split("\n")) {
    if (line.startsWith("diff --git ")) {
      if (file) flush();
      file = { path: line.replace(/^diff --git a\/.+ b\//, ""), rows: [] };
      files.push(file);
    } else if (!file || line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("index ")
      || line.startsWith("new file") || line.startsWith("deleted file") || line.startsWith("\\")) {
      continue;
    } else if (line.startsWith("@@")) {
      flush();
      const m = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$/.exec(line);
      left = Number(m?.[1] ?? 0);
      right = Number(m?.[2] ?? 0);
      file.rows.push({ hunk: line });
    } else if (line.startsWith("-")) {
      removed.push({ number: left++, text: line.slice(1), kind: "removed" });
    } else if (line.startsWith("+")) {
      added.push({ number: right++, text: line.slice(1), kind: "added" });
    } else if (line.startsWith(" ")) {
      flush();
      file.rows.push({ left: { number: left++, text: line.slice(1), kind: "context" },
                       right: { number: right++, text: line.slice(1), kind: "context" } });
    }
  }
  if (file) flush();
  return files;
}
