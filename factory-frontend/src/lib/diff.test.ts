import { describe, expect, it } from "vitest";
import { splitDiff } from "./diff";

const patch = `diff --git a/totals.js b/totals.js
index 1111111..2222222 100644
--- a/totals.js
+++ b/totals.js
@@ -1,4 +1,5 @@
 // Sums invoice lines.
 export function total(lines) {
-  return lines.reduce((sum, l) => sum + round(l), 0);
+  const sum = lines.reduce((acc, l) => acc + l.price * l.qty, 0);
+  return Math.round(sum * 100) / 100;
 }
diff --git a/NEW.md b/NEW.md
new file mode 100644
--- /dev/null
+++ b/NEW.md
@@ -0,0 +1 @@
+hello
`;

describe("splitDiff", () => {
  it("faces removed lines with the added ones, numbered on each side", () => {
    const [totals, created] = splitDiff(patch);
    expect(totals.path).toBe("totals.js");
    expect(totals.rows[0]).toEqual({ hunk: "@@ -1,4 +1,5 @@" });
    expect(totals.rows[3]).toEqual({
      left: { number: 3, text: "  return lines.reduce((sum, l) => sum + round(l), 0);", kind: "removed" },
      right: { number: 3, text: "  const sum = lines.reduce((acc, l) => acc + l.price * l.qty, 0);", kind: "added" },
    });
    expect(totals.rows[4]).toEqual({ left: null, right: { number: 4, text: "  return Math.round(sum * 100) / 100;", kind: "added" } });
    expect(totals.rows[5]).toEqual({ left: { number: 4, text: "}", kind: "context" }, right: { number: 5, text: "}", kind: "context" } });
    expect(created).toEqual({ path: "NEW.md", rows: [{ hunk: "@@ -0,0 +1 @@" }, { left: null, right: { number: 1, text: "hello", kind: "added" } }] });
  });

  it("reads a removed SQL comment and a stripped blank line as lines, not headers", () => {
    const [sql] = splitDiff(`diff --git a/v1.sql b/v1.sql
--- a/v1.sql
+++ b/v1.sql
@@ -1,3 +1,3 @@
--- old note
+++ counter
 
 select 1;
`.replace("\n \n", "\n\n")); // the blank context line, its space stripped by an editor
    expect(sql.rows[1]).toEqual({ left: { number: 1, text: "-- old note", kind: "removed" }, right: { number: 1, text: "++ counter", kind: "added" } });
    expect(sql.rows[2]).toMatchObject({ left: { number: 2, text: "", kind: "context" } });
    expect(sql.rows[3]).toMatchObject({ left: { number: 3, text: "select 1;" }, right: { number: 3 } });
    expect(sql.rows).toHaveLength(4);
  });

  it("still splits files when a hand-made patch miscounts its hunk", () => {
    const files = splitDiff(patch.replace("@@ -1,4 +1,5 @@", "@@ -1,9 +1,9 @@"));
    expect(files.map((f) => f.path)).toEqual(["totals.js", "NEW.md"]);
    expect(files[0].rows.at(-1)).toMatchObject({ left: { text: "}" } });
  });
});
