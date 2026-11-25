package com.tink.zaobaoeink;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure page-slicing math for the article page: given per-line geometry of a
 * fully laid-out document (starts / ends / tops / bottoms, all parallel
 * arrays) and a per-page height budget, greedily pack whole lines into pages.
 *
 * Guarantees (verified on a plain JVM, no Android runtime needed):
 * - pages tile the text exactly: first page starts at 0, every page starts
 *   where the previous one ended, the last page ends at textLen — no line is
 *   ever dropped or duplicated between pages;
 * - every page holds at least one line (a single line taller than the budget
 *   still gets its own page, so the walk always terminates);
 * - no empty trailing page: a page is only started while lines remain.
 *
 * Deliberately free of Android types so the arithmetic can be unit-tested
 * off-device; ArticleActivity feeds it arrays read from a StaticLayout.
 */
final class Paginator {

    private Paginator() {
    }

    static List<int[]> slice(int[] lineStarts, int[] lineEnds,
                             int[] lineTops, int[] lineBottoms,
                             int pageH, int textLen) {
        List<int[]> result = new ArrayList<int[]>();
        int lines = lineStarts.length;
        int line = 0;
        while (line < lines) {
            int startLine = line;
            int top = lineTops[startLine];
            while (line < lines && lineBottoms[line] - top <= pageH) {
                line++;
            }
            if (line == startLine) line++; // one line taller than a page: take it anyway
            result.add(new int[]{lineStarts[startLine], lineEnds[line - 1]});
        }
        if (result.isEmpty()) {
            result.add(new int[]{0, textLen});
            return result;
        }
        // Tiling guards: pin the outer edges to the text bounds so the union
        // of all pages is always exactly [0, textLen).
        int[] first = result.get(0);
        if (first[0] != 0) first[0] = 0;
        int[] last = result.get(result.size() - 1);
        if (last[1] != textLen) last[1] = textLen;
        return result;
    }
}
