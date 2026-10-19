package com.anode.factory.prompt;

import java.util.ArrayList;
import java.util.List;

/** A line diff of two texts: unchanged lines prefixed "  ", removed "- ", added "+ ". */
final class LineDiff {

    private LineDiff() {}

    // ponytail: full LCS table, O(n*m) memory; fine for prompts, a Myers diff if texts grow to thousands of lines
    static List<String> of(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> lines = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.length || j < b.length) {
            if (i < a.length && j < b.length && a[i].equals(b[j])) {
                lines.add("  " + a[i++]);
                j++;
            } else if (i < a.length && (j == b.length || lcs[i + 1][j] >= lcs[i][j + 1])) {
                lines.add("- " + a[i++]); // removals first, as in unified diffs
            } else {
                lines.add("+ " + b[j++]);
            }
        }
        return lines;
    }
}
