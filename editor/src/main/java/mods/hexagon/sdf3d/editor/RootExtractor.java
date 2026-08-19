package mods.hexagon.sdf3d.editor;

/**
 * Finds the root SDF expression in a {@code .s3d} source document. The root is the last
 * top-level statement (after the {@code key = value;} directives and {@code group("x") {...}}
 * blocks). This lets sculpt brushes wrap the root without a full round-trip through the parser.
 */
public final class RootExtractor {
    private RootExtractor() {}

    public record Span(int start, int end) {}

    public static Span root(String source) {
        int i = 0;
        int n = source.length();
        int paren = 0;
        int brace = 0;
        boolean inString = false;
        boolean inLineComment = false;
        int stmtStart = -1;
        int lastStart = -1;
        int lastEnd = -1;

        while (i < n) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '"') inString = false;
                i++;
                continue;
            }
            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                inLineComment = true;
                i += 2;
                continue;
            }
            if (c == '(') paren++;
            else if (c == ')') paren--;
            else if (c == '{') brace++;
            else if (c == '}') brace--;
            else if (c == ';' && paren == 0 && brace == 0) {
                if (stmtStart >= 0) {
                    lastStart = stmtStart;
                    lastEnd = i; // root text excludes the trailing semicolon
                }
                stmtStart = -1;
            } else if (stmtStart < 0 && !Character.isWhitespace(c)) {
                stmtStart = i;
            }
            i++;
        }
        if (stmtStart >= 0) { // trailing statement without a semicolon
            lastStart = stmtStart;
            lastEnd = n;
            while (lastEnd > lastStart && Character.isWhitespace(source.charAt(lastEnd - 1))) lastEnd--;
        }
        if (lastStart < 0) throw new IllegalArgumentException("No root expression found");
        return new Span(lastStart, lastEnd);
    }
}
