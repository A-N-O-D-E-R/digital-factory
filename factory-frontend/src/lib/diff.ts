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
  // a hunk's body is as long as its header says: the only way to tell a removed "-- comment" from a file header,
  // or a blank context line (its space stripped) from the end of the patch. A file or hunk header still ends it,
  // should the counts be wrong (a hand-made patch): no body line starts with "d" or "@".
  let leftToGo = 0;
  let rightToGo = 0;
  for (const line of patch.replace(/\n$/, "").split("\n")) {
    if (line.startsWith("diff --git ")) {
      if (file) flush();
      leftToGo = rightToGo = 0;
      file = { path: line.replace(/^diff --git a\/.+ b\//, ""), rows: [] };
      files.push(file);
    } else if (file && line.startsWith("@@")) {
      flush();
      const m = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(line);
      left = Number(m?.[1] ?? 0);
      right = Number(m?.[3] ?? 0);
      leftToGo = Number(m?.[2] ?? 1);
      rightToGo = Number(m?.[4] ?? 1);
      file.rows.push({ hunk: line });
    } else if (file && (leftToGo > 0 || rightToGo > 0)) {
      if (line.startsWith("\\")) continue; // "\ No newline at end of file"
      if (line.startsWith("-")) {
        removed.push({ number: left++, text: line.slice(1), kind: "removed" });
        leftToGo--;
      } else if (line.startsWith("+")) {
        added.push({ number: right++, text: line.slice(1), kind: "added" });
        rightToGo--;
      } else {
        flush();
        const text = line.slice(1);
        file.rows.push({ left: { number: left++, text, kind: "context" }, right: { number: right++, text, kind: "context" } });
        leftToGo--;
        rightToGo--;
      }
    } // anything else is a file header: index, mode, ---/+++
  }
  if (file) flush();
  return files;
}
