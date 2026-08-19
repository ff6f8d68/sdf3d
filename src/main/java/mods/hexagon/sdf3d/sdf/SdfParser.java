package mods.hexagon.sdf3d.sdf;

import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for the {@code .s3d} model format. A {@code .s3d} file is, at its core, a single
 * signed-distance function written as nested function calls, e.g.:
 * <pre>
 *   texture = "sdf3d:textures/model/example";
 *   tint = 0xFFFFFFFF;
 *   emissive = false;
 *   roughness = 0.7;
 *
 *   smooth_union(0.1, sphere(0, 0, 0, 0.5), box(0, 0.3, 0, 0.4, 0.4, 0.4))
 * </pre>
 * Optional {@code key = value;} directives set the model-wide material. The final expression
 * (the only one) is the root SDF. Numeric literals, {@code x}/{@code y}/{@code z} coordinate
 * access and the arithmetic helpers {@code add, sub, mul, div, neg, abs, min, max} are all
 * supported, so a file may also be a raw function such as {@code sub(add(mul(x,x),mul(y,y),mul(z,z)), 1)}.
 */
public final class SdfParser {
    private SdfParser() {}

    public static SdfModel parse(ResourceLocation id, String source) {
        List<Token> tokens = new Lexer(source).lex();
        Parser p = new Parser(id, tokens);
        return p.parseModel();
    }

    // ------------------------------------------------------------------ tokens

    private enum TokenType { NUMBER, STRING, IDENT, LPAREN, RPAREN, COMMA, SEMI, EQ, LBRACE, RBRACE, EOF }

    private record Token(TokenType type, String text, double number) {}

    private static final class Lexer {
        private final String src;
        private int pos = 0;

        Lexer(String src) { this.src = src; }

        List<Token> lex() {
            List<Token> tokens = new ArrayList<>();
            while (true) {
                skipWhitespaceAndComments();
                if (pos >= src.length()) { tokens.add(new Token(TokenType.EOF, "", 0)); return tokens; }
                char c = src.charAt(pos);
                switch (c) {
                    case '(' -> { tokens.add(new Token(TokenType.LPAREN, "(", 0)); pos++; }
                    case ')' -> { tokens.add(new Token(TokenType.RPAREN, ")", 0)); pos++; }
                    case ',' -> { tokens.add(new Token(TokenType.COMMA, ",", 0)); pos++; }
                    case ';' -> { tokens.add(new Token(TokenType.SEMI, ";", 0)); pos++; }
                    case '=' -> { tokens.add(new Token(TokenType.EQ, "=", 0)); pos++; }
                    case '{' -> { tokens.add(new Token(TokenType.LBRACE, "{", 0)); pos++; }
                    case '}' -> { tokens.add(new Token(TokenType.RBRACE, "}", 0)); pos++; }
                    case '"' -> tokens.add(lexString());
                    default -> {
                        if (isDigit(c) || (c == '-' && pos + 1 < src.length() && isDigit(src.charAt(pos + 1)))) {
                            tokens.add(lexNumber());
                        } else if (isIdentStart(c)) {
                            tokens.add(lexIdent());
                        } else {
                            throw error("Unexpected character '" + c + "'");
                        }
                    }
                }
            }
        }

        private void skipWhitespaceAndComments() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) { pos++; continue; }
                if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                    pos += 2;
                    while (pos < src.length() && src.charAt(pos) != '\n') pos++;
                    continue;
                }
                break;
            }
        }

        private Token lexString() {
            int start = ++pos; // skip opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != '"') sb.append(src.charAt(pos++));
            if (pos >= src.length()) throw error("Unterminated string");
            pos++; // closing quote
            return new Token(TokenType.STRING, sb.toString(), 0);
        }

        private Token lexNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            if (pos + 1 < src.length() && src.charAt(pos) == '0' && (src.charAt(pos + 1) == 'x' || src.charAt(pos + 1) == 'X')) {
                pos += 2;
                while (pos < src.length() && isHex(src.charAt(pos))) pos++;
            } else {
                while (pos < src.length() && (isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            }
            String text = src.substring(start, pos);
            double value = parseNumber(text);
            return new Token(TokenType.NUMBER, text, value);
        }

        private Token lexIdent() {
            int start = pos;
            while (pos < src.length() && isIdentPart(src.charAt(pos))) pos++;
            return new Token(TokenType.IDENT, src.substring(start, pos), 0);
        }

        private double parseNumber(String text) {
            try {
                if (text.startsWith("0x") || text.startsWith("0X")) return Long.parseLong(text.substring(2), 16);
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw error("Invalid number '" + text + "'");
            }
        }

        private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
        private static boolean isHex(char c) { return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }
        private static boolean isIdentStart(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'; }
        private static boolean isIdentPart(char c) { return isIdentStart(c) || isDigit(c); }

        private IllegalArgumentException error(String message) {
            int line = 1, col = 1;
            for (int i = 0; i < pos && i < src.length(); i++) {
                if (src.charAt(i) == '\n') { line++; col = 1; } else col++;
            }
            return new IllegalArgumentException(message + " (line " + line + ", column " + col + ")");
        }
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        private final ResourceLocation id;
        private final List<Token> tokens;
        private int pos = 0;
        private SdfGraph.Builder builder = new SdfGraph.Builder();

        private ResourceLocation texture;
        private int tint = 0xFFFFFFFF;
        private boolean emissive = false;
        private float roughness = 0.7f;
        private float metallic = 0.0f;
        private final Map<String, SdfGraph> groups = new HashMap<>();
        private int easing = 0;
        private float[] bezier = {0.42f, 0.0f, 0.58f, 1.0f};

        Parser(ResourceLocation id, List<Token> tokens) {
            this.id = id;
            this.tokens = tokens;
        }

        SdfModel parseModel() {
            Integer root = null;
            while (peek().type() != TokenType.EOF) {
                if (peek().type() == TokenType.IDENT && peek(1).type() == TokenType.EQ) {
                    parseDirective();
                } else if (peek().type() == TokenType.IDENT && peek().text().equals("group")) {
                    parseGroup();
                } else {
                    if (root != null) throw error("Multiple root functions defined");
                    root = parseExpr();
                    if (peek().type() == TokenType.SEMI) pos++;
                }
            }
            if (root == null) throw error("No SDF function defined");
            SdfGraph graph = builder.build(root);
            graph.setEasing(easing, bezier[0], bezier[1], bezier[2], bezier[3]);
            SdfMaterial material = new SdfMaterial(texture, tint, emissive, roughness, metallic);
            return new SdfModel(id, graph, graph.bounds(), material);
        }

        /** Parses {@code group("name") { expr, expr, ... }} into a named union of shapes. */
        private void parseGroup() {
            expect(TokenType.IDENT); // "group"
            expect(TokenType.LPAREN);
            String name = expect(TokenType.STRING).text();
            expect(TokenType.RPAREN);
            expect(TokenType.LBRACE);

            SdfGraph.Builder saved = builder;
            builder = new SdfGraph.Builder();
            List<Integer> children = new ArrayList<>();
            if (peek().type() != TokenType.RBRACE) {
                children.add(parseExpr());
                while (peek().type() == TokenType.COMMA) {
                    pos++;
                    children.add(parseExpr());
                }
            }
            expect(TokenType.RBRACE);
            int root = unionChain(children);
            groups.put(name, builder.build(root));
            builder = saved;
        }

        /** Folds a list of children into a left-associative union. */
        private int unionChain(List<Integer> children) {
            if (children.isEmpty()) throw error("Empty group body");
            int result = children.get(0);
            for (int i = 1; i < children.size(); i++) {
                result = builder.addNode(SdfGraph.UNION, result, children.get(i));
            }
            return result;
        }

        private void parseDirective() {
            String key = expect(TokenType.IDENT).text();
            expect(TokenType.EQ);
            switch (key) {
                case "texture" -> {
                    Token t = expect(TokenType.STRING);
                    texture = ResourceLocation.parse(t.text());
                }
                case "tint" -> {
                    Token t = expect(TokenType.NUMBER);
                    tint = (int) parseLong(t.text());
                }
                case "emissive" -> emissive = parseBool();
                case "roughness" -> {
                    Token t = expect(TokenType.NUMBER);
                    roughness = (float) t.number();
                }
                case "metallic" -> {
                    Token t = expect(TokenType.NUMBER);
                    metallic = (float) t.number();
                }
                case "easing" -> {
                    if (peek().type() == TokenType.STRING
                            || (peek().type() == TokenType.IDENT && !peek().text().equals("bezier"))) {
                        Token t = expect(peek().type());
                        easing = easingCode(t.text());
                    } else {
                        expectIdent("bezier");
                        expect(TokenType.LPAREN);
                        float cx1 = expectFloat();
                        expect(TokenType.COMMA);
                        float cy1 = expectFloat();
                        expect(TokenType.COMMA);
                        float cx2 = expectFloat();
                        expect(TokenType.COMMA);
                        float cy2 = expectFloat();
                        expect(TokenType.RPAREN);
                        easing = 5;
                        bezier = new float[]{cx1, cy1, cx2, cy2};
                    }
                }
                default -> throw error("Unknown directive '" + key + "'");
            }
            expect(TokenType.SEMI);
        }

        private boolean parseBool() {
            Token t = expect(TokenType.IDENT);
            if (t.text().equals("true")) return true;
            if (t.text().equals("false")) return false;
            throw error("Expected true or false, got '" + t.text() + "'");
        }

        private int easingCode(String name) {
            return switch (name) {
                case "linear" -> 0;
                case "smoothstep" -> 1;
                case "easeIn" -> 2;
                case "easeOut" -> 3;
                case "easeInOut" -> 4;
                default -> throw error("Unknown easing '" + name + "'");
            };
        }

        private void expectIdent(String name) {
            Token t = expect(TokenType.IDENT);
            if (!t.text().equals(name)) throw error("Expected '" + name + "'");
        }

        private float expectFloat() {
            return (float) expect(TokenType.NUMBER).number();
        }

        private long parseLong(String text) {
            if (text.startsWith("0x") || text.startsWith("0X")) return Long.parseLong(text.substring(2), 16);
            return Long.parseLong(text);
        }

        private int parseExpr() {
            Token t = peek();
            if (t.type() == TokenType.NUMBER) {
                pos++;
                return builder.addNode(SdfGraph.CONST, -1, -1, (float) t.number());
            }
            if (t.type() == TokenType.IDENT) {
                pos++;
                String name = t.text();
                if (peek().type() == TokenType.LPAREN) {
                    if (name.equals("group")) {
                        return parseGroupReference();
                    }
                    return parseCall(name);
                }
                return switch (name) {
                    case "x" -> builder.addNode(SdfGraph.X, -1, -1);
                    case "y" -> builder.addNode(SdfGraph.Y, -1, -1);
                    case "z" -> builder.addNode(SdfGraph.Z, -1, -1);
                    default -> throw error("Unknown identifier '" + name + "'");
                };
            }
            throw error("Expected expression, got " + t.type());
        }

        /** Inlines a named group (duplicating its subtree) as an expression. */
        private int parseGroupReference() {
            expect(TokenType.LPAREN);
            String name = expect(TokenType.STRING).text();
            expect(TokenType.RPAREN);
            SdfGraph group = groups.get(name);
            if (group == null) throw error("Unknown group '" + name + "'");
            return builder.append(group);
        }

        private int parseCall(String name) {
            expect(TokenType.LPAREN);
            List<Object> args = new ArrayList<>();
            if (peek().type() != TokenType.RPAREN) {
                args.add(parseArg());
                while (peek().type() == TokenType.COMMA) {
                    pos++;
                    args.add(parseArg());
                }
            }
            expect(TokenType.RPAREN);
            return emitCall(name, args);
        }

        /**
         * Parses one argument. A pure-constant subtree (a bare number, or arithmetic over
         * numbers) is folded to a float and its nodes removed from the graph immediately - the
         * value is baked into the parent's parameters instead of lingering as CONST nodes, which
         * keeps models small enough for the generic uniform-array render path.
         */
        private Object parseArg() {
            int start = builder.nodeCount();
            int index = parseExpr();
            Float value = fold(index);
            if (value != null) {
                builder.pop(builder.nodeCount() - start);
                return value;
            }
            return index;
        }

        private int emitCall(String name, List<Object> args) {
            switch (name) {
                case "sphere": return primitive(SdfGraph.SPHERE, args, 4);
                case "box": return primitive(SdfGraph.BOX, args, 6);
                case "rounded_box": return primitive(SdfGraph.ROUNDED_BOX, args, 7);
                case "ellipsoid": return primitive(SdfGraph.ELLIPSOID, args, 6);
                case "torus": return primitive(SdfGraph.TORUS, args, 5);
                case "capsule": return primitive(SdfGraph.CAPSULE, args, 7);
                case "plane": {
                    requireArity(args, 4);
                    float nx = num(args.get(0)), ny = num(args.get(1)), nz = num(args.get(2));
                    Vector3f n = new Vector3f(nx, ny, nz).normalize();
                    return builder.addNode(SdfGraph.PLANE, -1, -1, n.x, n.y, n.z, num(args.get(3)));
                }
                case "cylinder": {
                    requireArity(args, 5);
                    return builder.addNode(SdfGraph.CYLINDER, -1, -1,
                            num(args.get(0)), num(args.get(1)), num(args.get(2)), num(args.get(3)), num(args.get(4)) * 0.5f);
                }
                case "union": return fold(SdfGraph.UNION, args, 0, 2);
                case "intersect": return fold(SdfGraph.INTERSECT, args, 0, 2);
                case "subtract": return binary(SdfGraph.SUBTRACT, args);
                // Sculpting brushes:
                case "carve": return binary(SdfGraph.SUBTRACT, args); // carve(base, brush)
                case "stamp": return binary(SdfGraph.UNION, args);     // stamp(base, brush) = add material
                case "blend": { // blend(k, a, b) = smooth_union(k, a, b)
                    requireArity(args, 3);
                    return builder.addNode(SdfGraph.SMOOTH_UNION, child(args.get(1)), child(args.get(2)), num(args.get(0)));
                }
                case "smooth_union": return fold(SdfGraph.SMOOTH_UNION, args, 1, 3);
                case "smooth_intersect": {
                    // smax(a,b,k) = -smin(-a,-b,k)
                    requireArity(args, 3);
                    float k = num(args.get(0));
                    int na = builder.addNode(SdfGraph.NEG, child(args.get(1)), -1);
                    int nb = builder.addNode(SdfGraph.NEG, child(args.get(2)), -1);
                    int smin = builder.addNode(SdfGraph.SMOOTH_UNION, na, nb, k);
                    return builder.addNode(SdfGraph.NEG, smin, -1);
                }
                case "smooth_subtract": {
                    // smax(a,-b,k) = -smin(-a,b,k)
                    requireArity(args, 3);
                    float k = num(args.get(0));
                    int na = builder.addNode(SdfGraph.NEG, child(args.get(1)), -1);
                    int smin = builder.addNode(SdfGraph.SMOOTH_UNION, na, child(args.get(2)), k);
                    return builder.addNode(SdfGraph.NEG, smin, -1);
                }
                case "translate": return unaryTransform(SdfGraph.TRANSLATE, args, 4);
                case "scale": {
                    requireArity(args, 4);
                    float sx = num(args.get(0)), sy = num(args.get(1)), sz = num(args.get(2));
                    int c = child(args.get(3));
                    if (Math.abs(sx - sy) < 1e-6f && Math.abs(sy - sz) < 1e-6f) {
                        return builder.addNode(SdfGraph.SCALE, c, -1, sx);
                    }
                    return builder.addNode(SdfGraph.STRETCH, c, -1, sx, sy, sz);
                }
                case "stretch": return unaryTransform(SdfGraph.STRETCH, args, 4);
                case "rotate": {
                    requireArity(args, 4);
                    Quaternionf q = new Quaternionf().rotateXYZ(
                            (float) Math.toRadians(num(args.get(0))),
                            (float) Math.toRadians(num(args.get(1))),
                            (float) Math.toRadians(num(args.get(2)))).conjugate();
                    Matrix3f m = new Matrix3f().set(q);
                    return builder.addNode(SdfGraph.ROTATE, child(args.get(3)), -1,
                            m.m00(), m.m01(), m.m02(), m.m10(), m.m11(), m.m12(), m.m20(), m.m21(), m.m22());
                }
                case "round": return unaryTransform(SdfGraph.ROUND, args, 2);
                case "onion": return unaryTransform(SdfGraph.ONION, args, 2);
                case "cone": {
                    // cone(x, y, z, radius, height): pointed tip at +y
                    requireArity(args, 5);
                    return builder.addNode(SdfGraph.CONE, -1, -1,
                            num(args.get(0)), num(args.get(1)), num(args.get(2)),
                            num(args.get(3)), num(args.get(4)) * 0.5f);
                }
                case "hex_prism": {
                    // hex_prism(x, y, z, radius, height): circumradius + full height
                    requireArity(args, 5);
                    return builder.addNode(SdfGraph.HEX_PRISM, -1, -1,
                            num(args.get(0)), num(args.get(1)), num(args.get(2)),
                            num(args.get(3)), num(args.get(4)) * 0.5f);
                }
                case "octahedron": {
                    requireArity(args, 4);
                    return builder.addNode(SdfGraph.OCTAHEDRON, -1, -1,
                            num(args.get(0)), num(args.get(1)), num(args.get(2)), num(args.get(3)));
                }
                case "spin": {
                    // spin(axisX, axisY, axisZ, degreesPerSecond, child)
                    requireArity(args, 5);
                    Vector3f axis = new Vector3f(num(args.get(0)), num(args.get(1)), num(args.get(2))).normalize();
                    return builder.addNode(SdfGraph.SPIN, child(args.get(4)), -1, axis.x, axis.y, axis.z, num(args.get(3)));
                }
                case "bob": {
                    // bob(axisX, axisY, axisZ, amplitude, cyclesPerSecond, child)
                    requireArity(args, 6);
                    Vector3f axis = new Vector3f(num(args.get(0)), num(args.get(1)), num(args.get(2))).normalize();
                    return builder.addNode(SdfGraph.BOB, child(args.get(5)), -1,
                            axis.x, axis.y, axis.z, num(args.get(3)), num(args.get(4)));
                }
                case "pulse": {
                    // pulse(minScale, maxScale, cyclesPerSecond, child)
                    requireArity(args, 4);
                    float lo = Math.max(0.0001f, num(args.get(0)));
                    float hi = Math.max(lo, num(args.get(1)));
                    return builder.addNode(SdfGraph.PULSE, child(args.get(3)), -1, lo, hi, num(args.get(2)));
                }
                case "morph": {
                    // morph(a, b, periodSeconds): cyclical crossfade between two SDFs
                    requireArity(args, 3);
                    float period = Math.max(1e-4f, num(args.get(2)));
                    return builder.addNode(SdfGraph.MORPH, child(args.get(0)), child(args.get(1)), period);
                }
                case "twist": {
                    // twist(radiansPerUnitY, child): twist the child around +Y
                    requireArity(args, 2);
                    return builder.addNode(SdfGraph.TWIST, child(args.get(1)), -1, num(args.get(0)));
                }
                case "bend": {
                    // bend(radiansPerUnitX, child): bend the child around +Z
                    requireArity(args, 2);
                    return builder.addNode(SdfGraph.BEND, child(args.get(1)), -1, num(args.get(0)));
                }
                case "keyframe_scale": {
                    // keyframe_scale(period, t0,v0,t1,v1,t2,v2,t3,v3, child)
                    requireArity(args, 10);
                    float period = Math.max(1e-4f, num(args.get(0)));
                    return builder.addNode(SdfGraph.KEYFRAME_SCALE, child(args.get(9)), -1,
                            period,
                            num(args.get(1)), num(args.get(2)), num(args.get(3)), num(args.get(4)),
                            num(args.get(5)), num(args.get(6)), num(args.get(7)), num(args.get(8)));
                }
                case "keyframe_translate": {
                    // keyframe_translate(period, t0,x0,y0,z0, t1,x1,y1,z1, child)
                    requireArity(args, 10);
                    return builder.addNode(SdfGraph.KEYFRAME_TRANSLATE, child(args.get(9)), -1,
                            Math.max(1e-4f, num(args.get(0))),
                            num(args.get(1)), num(args.get(2)), num(args.get(3)), num(args.get(4)),
                            num(args.get(5)), num(args.get(6)), num(args.get(7)), num(args.get(8)));
                }
                case "keyframe_rotate": {
                    // keyframe_rotate(axisX, axisY, axisZ, period, t0, angle0, t1, angle1, child)
                    requireArity(args, 9);
                    Vector3f axis = new Vector3f(num(args.get(0)), num(args.get(1)), num(args.get(2))).normalize();
                    return builder.addNode(SdfGraph.KEYFRAME_ROTATE, child(args.get(8)), -1,
                            axis.x, axis.y, axis.z,
                            Math.max(1e-4f, num(args.get(3))),
                            num(args.get(4)), num(args.get(5)),
                            num(args.get(6)), num(args.get(7)));
                }
                case "add": return fold(SdfGraph.ADD, args, 0, 2);
                case "sub": return binary(SdfGraph.SUB, args);
                case "mul": return fold(SdfGraph.MUL, args, 0, 2);
                case "div": return binary(SdfGraph.DIV, args);
                case "neg": { requireArity(args, 1); return builder.addNode(SdfGraph.NEG, child(args.get(0)), -1); }
                case "abs": { requireArity(args, 1); return builder.addNode(SdfGraph.ABS, child(args.get(0)), -1); }
                case "min": return fold(SdfGraph.MIN, args, 0, 2);
                case "max": return fold(SdfGraph.MAX, args, 0, 2);
                default: throw error("Unknown function '" + name + "'");
            }
        }

        private int primitive(int op, List<Object> args, int arity) {
            requireArity(args, arity);
            float[] params = new float[arity];
            for (int i = 0; i < arity; i++) params[i] = num(args.get(i));
            return builder.addNode(op, -1, -1, params);
        }

        private int binary(int op, List<Object> args) {
            requireArity(args, 2);
            return builder.addNode(op, child(args.get(0)), child(args.get(1)));
        }

        private int binaryK(int op, List<Object> args) {
            requireArity(args, 3);
            return builder.addNode(op, child(args.get(1)), child(args.get(2)), num(args.get(0)));
        }

        /** A transform where all args are numbers except the last, which is the child. */
        private int unaryTransform(int op, List<Object> args, int arity) {
            requireArity(args, arity);
            float[] params = new float[arity - 1];
            for (int i = 0; i < arity - 1; i++) params[i] = num(args.get(i));
            return builder.addNode(op, child(args.get(arity - 1)), -1, params);
        }

        /** Folds an n-ary op into a binary chain. {@code numberCount} leading args are numbers (e.g. the k of smooth_union). */
        private int fold(int op, List<Object> args, int numberCount, int minArity) {
            if (args.size() < minArity) throw error("Too few arguments");
            List<Float> numbers = new ArrayList<>();
            List<Object> children = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                if (i < numberCount) numbers.add(num(args.get(i)));
                else children.add(args.get(i));
            }
            if (children.isEmpty()) throw error("Expected at least one child");
            int result = child(children.get(0));
            for (int i = 1; i < children.size(); i++) {
                if (op == SdfGraph.SMOOTH_UNION) result = builder.addNode(op, result, child(children.get(i)), numbers.get(0));
                else result = builder.addNode(op, result, child(children.get(i)));
            }
            return result;
        }

        private void requireArity(List<Object> args, int arity) {
            if (args.size() != arity) throw error("Expected " + arity + " arguments, got " + args.size());
        }

        /**
         * A child operand: a node index, or a folded number promoted back to a CONST node
         * (needed for arithmetic like {@code sub(shape, 1)}, where the number is an operand
         * rather than a baked-in parameter).
         */
        private int child(Object arg) {
            if (arg instanceof Integer index) return index;
            return builder.addNode(SdfGraph.CONST, -1, -1, (Float) arg);
        }

        private float num(Object arg) {
            if (arg instanceof Float value) return value;
            Float folded = fold((Integer) arg);
            if (folded == null) throw error("Expected a number");
            return folded;
        }

        /** Constant-folds a subtree composed purely of CONST and arithmetic nodes. */
        private Float fold(int index) {
            int op = builder.op(index);
            switch (op) {
                case SdfGraph.CONST: return builder.param(index, 0);
                case SdfGraph.ADD: return binFold(builder.childA(index), builder.childB(index), (a, b) -> a + b);
                case SdfGraph.SUB: return binFold(builder.childA(index), builder.childB(index), (a, b) -> a - b);
                case SdfGraph.MUL: return binFold(builder.childA(index), builder.childB(index), (a, b) -> a * b);
                case SdfGraph.DIV: return binFold(builder.childA(index), builder.childB(index), (a, b) -> a / b);
                case SdfGraph.MIN: return binFold(builder.childA(index), builder.childB(index), Math::min);
                case SdfGraph.MAX: return binFold(builder.childA(index), builder.childB(index), Math::max);
                case SdfGraph.NEG: { Float f = fold(builder.childA(index)); return f == null ? null : -f; }
                case SdfGraph.ABS: { Float f = fold(builder.childA(index)); return f == null ? null : Math.abs(f); }
                default: return null;
            }
        }

        private Float binFold(int a, int b, BinOp op) {
            Float fa = fold(a), fb = fold(b);
            if (fa == null || fb == null) return null;
            return op.apply(fa, fb);
        }

        @FunctionalInterface
        private interface BinOp { float apply(float a, float b); }

        private Token peek() { return tokens.get(pos); }
        private Token peek(int offset) { return tokens.get(Math.min(pos + offset, tokens.size() - 1)); }

        private Token expect(TokenType type) {
            Token t = peek();
            if (t.type() != type) throw error("Expected " + type + ", got " + t.type());
            pos++;
            return t;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("sdf3d model '" + id + "': " + message);
        }
    }
}
