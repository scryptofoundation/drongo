package com.sparrowwallet.drongo.evm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A strict subset of SVG, read into a small immutable drawing model. An NFT's picture is chosen by whoever deployed the
 * contract, and SVG can carry scripts, external references, entity bombs and layout bombs, so only plain shapes with
 * plain paint and transforms are read. Anything outside the subset refuses the whole file ({@link #parse} is empty and
 * the caller shows a placeholder): nothing is repaired and nothing is drawn in part. The reader is hand-written, uses
 * no XML library, never recurses and is bounded everywhere. This contract is shared with the phone's port, which must
 * accept and refuse the same vectors (src/test/resources/com/sparrowwallet/drongo/evm/svg) and describe each accepted
 * one with the same lines (expected.json).
 *
 * Input. At most 100,000 UTF-16 characters, checked first; every character a legal XML 1.0 character (tab, line feed,
 * carriage return, U+0020 and above, surrogates only as pairs, never U+FFFE or U+FFFF). An XML declaration
 * (version, then optional encoding and standalone, quoted values of letters, digits, '.', '_' or '-'; the encoding,
 * when named, must be UTF-8 in any case) is allowed only at the very start. Comments are allowed anywhere and skipped (they must not contain "--"): a comment can hide nothing
 * from a reader that refuses every unknown construct. Refused: a DOCTYPE or any other "&lt;!" construct, CDATA, any
 * other processing instruction, a byte order mark or any text before or after the root, and character data inside
 * svg, g or a shape other than literal whitespace. Entities: only lt, gt, amp, quot, apos and decimal or hexadecimal
 * character references (at most 7 or 6 digits) that name a legal XML character; anything else refuses. Tags are
 * well formed: names are ASCII ([A-Za-z_][A-Za-z0-9._:-]*, at most 64), case sensitive, attribute values quoted,
 * whitespace between attributes, no duplicates, no '&lt;' in a value, every element closed by its own end tag.
 *
 * Elements. svg (the root, and only the root), g, rect, circle, ellipse, line, polyline, polygon, path, text, title,
 * desc. svg and g hold g, shapes, text, title and desc; shapes hold only title and desc; text, title and desc hold only
 * text. At most 2,000 elements (the root counted) and 32 levels of nesting (the root and the innermost counted).
 * A prefixed element refuses.
 *
 * Attributes. Every decoded value is checked before anything else reads it: one containing "url(" or "javascript:",
 * in any case, refuses. Then, by element:
 *   paint (every element but title and desc): id, fill, stroke, stroke-width, opacity, fill-opacity, stroke-opacity;
 *   font (svg, g, text): font-family, font-size, font-weight, text-anchor;
 *   svg: viewBox, width, height, version (1.0, 1.1 or 2.0), shape-rendering, xmlns (required, as a browser requires
 *        it to draw the file as SVG; the Vault Badge declares it) holding exactly http://www.w3.org/2000/svg, and
 *        xmlns:xlink holding exactly http://www.w3.org/1999/xlink;
 *   g: transform, shape-rendering;
 *   rect: transform, shape-rendering, x, y, width, height, rx, ry;  circle: ... cx, cy, r;  ellipse: ... cx, cy, rx, ry;
 *   line: ... x1, y1, x2, y2;  polyline, polygon: ... points;  path: ... d;  text: transform, x, y;  title, desc: id.
 * id is accepted and ignored, and so is shape-rendering (auto, optimizeSpeed, crispEdges or geometricPrecision, which
 * the Vault Badge sets on its root). Any other attribute refuses (style, class, href, on*, filter, mask, clip-path,
 * preserveAspectRatio, dx, ...), as does any other namespace declaration, xmlns anywhere but the root, and any other
 * prefixed attribute (xlink:href, xml:space).
 *
 * Numbers. Sign, digits, optional fraction, optional exponent of at most 3 digits, where 'e' or 'E' belongs to the
 * number only when a digit, or a sign and a digit, follows it, as in CSS (so "1em" is 1 followed by "em", which then
 * refuses as a unit). "5.", ".5", "+5" and "1e-400", which is 0, are numbers; "NaN", "Infinity", "0x10", "1e0001" and
 * "1,5" as one value are not. A token is at most 64 characters,
 * converted with Double.parseDouble, and must be finite with a magnitude of at most 1,000,000. Single lengths (x, r,
 * width, stroke-width, font-size, ...) may end in "px"; percentages and other units refuse. Sizes, radii and
 * stroke-width must not be negative; font-size must be above 0 and at most 4,096. Opacity values clamp to 0..1. Lists
 * (viewBox, points, transform arguments, path data) are separated by whitespace and at most one comma.
 *
 * Size. viewBox (four numbers, width and height at least 1 and at most 4,096) gives the drawing's size, and a
 * non-zero min-x or min-y becomes an outermost translate(-min-x, -min-y) on every shape; without a viewBox, width and
 * height must both be present. width and height, when present, are numbers or px of at least 1 and at most 4,096.
 *
 * Paint and inheritance. Colours: #rgb, #rrggbb, rgb(r, g, b) with integers 0..255, none, and the 16 basic names
 * (black, silver, gray, white, maroon, red, purple, fuchsia, green, lime, olive, yellow, navy, blue, teal, aqua), ASCII
 * case-insensitive; anything else refuses. fill, stroke, stroke-width, fill-opacity, stroke-opacity and the font
 * attributes are inherited; an element's own value wins. opacity multiplies down the tree (a group's opacity is
 * applied to each child, which differs from SVG's group compositing only where children overlap). Defaults: fill black,
 * stroke none, stroke-width 1, opacities 1, font-size 16, not bold, not monospace, anchor start. A shape's Paint is
 * argb with alpha = Math.round(opacity * 255) of its fill-opacity or stroke-opacity; none is null.
 *
 * Transforms. translate(x [y]), scale(x [y]), rotate(a [cx cy]) and matrix(a b c d e f), at most 16 per attribute.
 * Each becomes an affine matrix {a, b, c, d, e, f} mapping (x, y) to (a x + c y + e, b x + d y + f). A shape's list is
 * the viewBox translation, then each ancestor's transforms, then its own, outermost first. Rotation: turn = a % 360,
 * plus 360 when negative; turns of exactly 0, 90, 180 and 270 use exact cos and sin; otherwise rad = turn / 180.0 *
 * Math.PI, cos = StrictMath.cos(rad), sin = StrictMath.sin(rad); the matrix is {cos, sin, -sin, cos, e, f} with
 * e = cx - cos * cx + sin * cy and f = cy - sin * cx - cos * cy, evaluated left to right (both 0 without a centre).
 * The composed matrix is the running product, starting from the identity: the viewBox translation, then each transform
 * in turn, outermost first, p followed by t giving {p.a t.a + p.c t.b, p.b t.a + p.d t.b, p.a t.c + p.c t.d,
 * p.b t.c + p.d t.d, p.a t.e + p.c t.f + p.e, p.b t.e + p.d t.f + p.f}, each entry evaluated left to right. Every
 * entry of every intermediate product must stay within 1,000,000,000, or the file refuses, so the composed matrix is
 * finite and bounded by construction. It is the model's one placement per shape (Shape.composedMatrix()); the list it
 * came from stays available (Shape.transforms()), but the bound says nothing about other orders: under outer groups
 * that shrink by 1e-300, inner ones may grow by up to 1e309 and overflow when multiplied innermost first, while the
 * composed matrix stays finite (transform-tiny-then-huge.svg is accepted: 1e-300 outside, 1e306 inside, product 1e6).
 *
 * Shapes. Missing geometry is 0. rect rx and ry: a missing one takes the other's value, then each is limited to half
 * the width or height. polyline and polygon need an even number of coordinates, at least one point and at most
 * 10,000. Path data: M L H V C S Q T A Z in both cases, implicit repetition (after a moveto, lineto), arc flags as
 * single '0' or '1' characters, negative arc radii refuse; it must start with a moveto (a first "m" is absolute) and
 * hold at most 10,000 segments, Close and each repetition counted. Segments are stored absolute: H and V become LineTo,
 * S and T get their reflected control point (or the current point after any other command), and every computed
 * coordinate must stay within 1,000,000. Text: x, y, font-size, text-anchor (start, middle, end), font-weight (normal,
 * bold, or 1..1000 where 600 and above is bold), font-family (a comma-separated list of names from a short list, no
 * quotes; monospace when the first is a monospace family). Its content is decoded, then tab, line feed, carriage
 * return and space become a single space between words and none at the ends, and every code point in the fixed table
 * {@link TextRules} STRIPPED (shared with NFT metadata text) is removed: C0 and C1 controls and DEL, U+2028 and U+2029, the variation selectors U+FE00..U+FE0F and
 * U+E0100..U+E01EF, and every format (Cf) character as of Unicode 15.1 (soft hyphen, Arabic number signs, the letter
 * mark, zero-width characters, bidirectional marks, overrides and isolates, invisible operators, BOM, interlinear
 * annotation, Egyptian format controls, shorthand and musical format controls, tags). The table is the rule, not any
 * platform's Unicode data. More than 200 code points left refuses.
 *
 * Stable text form ({@link #describe}): a line "size W H", then one line per shape in document order:
 *   rect x= y= w= h= rx= ry= | circle cx= cy= r= | ellipse cx= cy= rx= ry= | line x1= y1= x2= y2=
 *   polyline points=[x y;x y] | polygon points=[...] | path d=[M x y;L x y;C x1 y1 x2 y2 x y;Q x1 y1 x y;
 *   A rx ry rotation large sweep x y;Z] (flags 1 or 0) | text x= y= size= anchor=start|middle|end bold= mono=
 * each followed by " fill=P stroke=P sw=N op=N m=[a b c d e f]" where P is "none" or "#AARRGGBB" in upper-case hex
 * and m is the composed matrix (the transform list itself is not printed); a text line then ends with " content=" and
 * its content verbatim. Numbers print as
 * new BigDecimal(value).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString(), and "0" for zero
 * (so -0 and tiny values print "0"): exact binary value, rounded once, no locale.
 *
 * Drawing it (every renderer, the phone's included):
 *   - place each shape with its composed matrix only, after the fit to the canvas (scale to fit, centred), and clip to
 *     the drawing's rectangle (0, 0, width, height) in drawing coordinates;
 *   - fill rule non-zero; stroke with butt caps, miter joins with a miter limit of 4; skip a stroke of width 0;
 *   - skip a rect, circle or ellipse with a zero width, height or radius; never fill a line; fill a polyline as if
 *     closed but stroke it open; a polygon is closed for both;
 *   - convert each arc to cubic Beziers per the SVG implementation notes (appendix F.6): a zero radius is a straight
 *     line to the end point, and radii too small to reach the end point are scaled up;
 *   - text: draw at (x, y) as the baseline start, offset by the anchor, in a monospace font when mono, else the
 *     platform's sans-serif; bold as given.
 */
public final class SvgSubset {
    public static final int MAX_CHARS = 100_000;
    public static final int MAX_ELEMENTS = 2_000;
    public static final int MAX_DEPTH = 32;
    public static final double MAX_SIDE = 4_096;
    public static final double MIN_SIDE = 1;
    public static final double MAX_FONT_SIZE = 4_096;
    public static final double MAX_MAGNITUDE = 1_000_000;
    public static final int MAX_PATH_SEGMENTS = 10_000;
    public static final int MAX_POINTS = 10_000;
    public static final int MAX_TRANSFORMS = 16;
    public static final double MAX_COMPOSED = 1_000_000_000;
    public static final int MAX_TEXT = 200;
    static final int MAX_NUMBER_CHARS = 64;
    static final int MAX_NAME_CHARS = 64;

    private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";
    private static final String XLINK_NAMESPACE = "http://www.w3.org/1999/xlink";
    private static final int NONE = -1;

    private static final Set<String> SHAPES = Set.of("rect", "circle", "ellipse", "line", "polyline", "polygon", "path");
    private static final Map<String, Set<String>> ATTRIBUTES = Map.ofEntries(
            Map.entry("svg", attributes(true, true, "viewBox", "width", "height", "version", "shape-rendering")),
            Map.entry("g", attributes(true, true, "transform", "shape-rendering")),
            Map.entry("rect", attributes(true, false, "transform", "shape-rendering", "x", "y", "width", "height", "rx", "ry")),
            Map.entry("circle", attributes(true, false, "transform", "shape-rendering", "cx", "cy", "r")),
            Map.entry("ellipse", attributes(true, false, "transform", "shape-rendering", "cx", "cy", "rx", "ry")),
            Map.entry("line", attributes(true, false, "transform", "shape-rendering", "x1", "y1", "x2", "y2")),
            Map.entry("polyline", attributes(true, false, "transform", "shape-rendering", "points")),
            Map.entry("polygon", attributes(true, false, "transform", "shape-rendering", "points")),
            Map.entry("path", attributes(true, false, "transform", "shape-rendering", "d")),
            Map.entry("text", attributes(true, true, "transform", "x", "y")),
            Map.entry("title", attributes(false, false)),
            Map.entry("desc", attributes(false, false)));

    private static final Map<String, Integer> COLOUR_NAMES = Map.ofEntries(Map.entry("black", 0x000000), Map.entry("silver", 0xC0C0C0),
            Map.entry("gray", 0x808080), Map.entry("white", 0xFFFFFF), Map.entry("maroon", 0x800000), Map.entry("red", 0xFF0000),
            Map.entry("purple", 0x800080), Map.entry("fuchsia", 0xFF00FF), Map.entry("green", 0x008000), Map.entry("lime", 0x00FF00),
            Map.entry("olive", 0x808000), Map.entry("yellow", 0xFFFF00), Map.entry("navy", 0x000080), Map.entry("blue", 0x0000FF),
            Map.entry("teal", 0x008080), Map.entry("aqua", 0x00FFFF));

    private static final Set<String> MONOSPACE_FAMILIES = Set.of("monospace", "ui-monospace", "courier", "courier new", "consolas", "menlo",
            "monaco", "lucida console", "dejavu sans mono", "liberation mono", "roboto mono", "sf mono", "source code pro", "fira code",
            "fira mono", "jetbrains mono", "ibm plex mono", "andale mono");
    private static final Set<String> OTHER_FAMILIES = Set.of("serif", "sans-serif", "system-ui", "cursive", "fantasy", "ui-serif",
            "ui-sans-serif", "arial", "helvetica", "helvetica neue", "verdana", "tahoma", "trebuchet ms", "georgia", "times",
            "times new roman", "segoe ui", "roboto", "inter", "impact");
    private static final Set<String> SHAPE_RENDERING = Set.of("auto", "optimizespeed", "crispedges", "geometricprecision");
    private static final Set<String> VERSIONS = Set.of("1.0", "1.1", "2.0");

    private SvgSubset() {
    }

    public record Drawing(double width, double height, List<Shape> shapes) {
        public Drawing {
            shapes = List.copyOf(shapes);
        }
    }

    /** A colour as 0xAARRGGBB; a shape's paint is null when it is none. */
    public record Paint(int argb) {
    }

    /**
     * A shape's resolved paint and its placement. composedMatrix() is the one matrix to draw with: {a, b, c, d, e, f}
     * mapping the shape's own coordinates to the drawing's (0..width, 0..height), every entry finite and within
     * MAX_COMPOSED. transforms() is the list it was composed from (each {a, b, c, d, e, f}, outermost first), kept for
     * inspection only: applying it in another order can overflow. Shapes in one group share that list; it is handed out
     * as an unmodifiable view whose get() returns a copy of one entry.
     */
    public static final class Style {
        private final Paint fill;
        private final Paint stroke;
        private final double strokeWidth;
        private final double opacity;
        private final List<double[]> transforms;
        private final double[] matrix;
        private final List<double[]> view;

        private Style(Paint fill, Paint stroke, double strokeWidth, double opacity, List<double[]> transforms, double[] matrix) {
            this.fill = fill;
            this.stroke = stroke;
            this.strokeWidth = strokeWidth;
            this.opacity = opacity;
            this.transforms = transforms;
            this.matrix = matrix.clone();
            this.view = new AbstractList<>() {
                @Override
                public double[] get(int index) {
                    return transforms.get(index).clone();
                }

                @Override
                public int size() {
                    return transforms.size();
                }
            };
        }

        /** The composed affine matrix {a, b, c, d, e, f} (SVG order), a copy. */
        public double[] composedMatrix() {
            return matrix.clone();
        }

        public Paint fill() {
            return fill;
        }

        public Paint stroke() {
            return stroke;
        }

        public double strokeWidth() {
            return strokeWidth;
        }

        public double opacity() {
            return opacity;
        }

        public List<double[]> transforms() {
            return view;
        }

        @Override
        public boolean equals(Object o) {
            if(!(o instanceof Style other) || !Objects.equals(fill, other.fill) || !Objects.equals(stroke, other.stroke)
                    || Double.compare(strokeWidth, other.strokeWidth) != 0 || Double.compare(opacity, other.opacity) != 0
                    || !Arrays.equals(matrix, other.matrix) || transforms.size() != other.transforms.size()) {
                return false;
            }
            for(int i = 0; i < transforms.size(); i++) {
                if(!Arrays.equals(transforms.get(i), other.transforms.get(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = Objects.hash(fill, stroke, strokeWidth, opacity) * 31 + Arrays.hashCode(matrix);
            for(double[] transform : transforms) {
                hash = 31 * hash + Arrays.hashCode(transform);
            }
            return hash;
        }

        @Override
        public String toString() {
            return "Style[fill=" + fill + ", stroke=" + stroke + ", strokeWidth=" + strokeWidth + ", opacity=" + opacity + ", matrix="
                    + Arrays.toString(matrix) + ", transforms=" + transforms.size() + "]";
        }
    }

    public sealed interface Shape permits Rect, Circle, Ellipse, Line, Polyline, PathShape, Text {
        Style style();

        default Paint fill() {
            return style().fill();
        }

        default Paint stroke() {
            return style().stroke();
        }

        default double strokeWidth() {
            return style().strokeWidth();
        }

        default double opacity() {
            return style().opacity();
        }

        default List<double[]> transforms() {
            return style().transforms();
        }

        /** The one matrix to draw this shape with, {a, b, c, d, e, f} in SVG order; a copy. */
        default double[] composedMatrix() {
            return style().composedMatrix();
        }
    }

    public record Rect(double x, double y, double width, double height, double rx, double ry, Style style) implements Shape {
    }

    public record Circle(double cx, double cy, double r, Style style) implements Shape {
    }

    public record Ellipse(double cx, double cy, double rx, double ry, Style style) implements Shape {
    }

    public record Line(double x1, double y1, double x2, double y2, Style style) implements Shape {
    }

    /** Points as x0, y0, x1, y1, ...; closed for a polygon. */
    public record Polyline(double[] points, boolean closed, Style style) implements Shape {
        public Polyline {
            points = points.clone();
        }

        @Override
        public double[] points() {
            return points.clone();
        }
    }

    public record PathShape(List<Segment> segments, Style style) implements Shape {
        public PathShape {
            segments = List.copyOf(segments);
        }
    }

    public enum Anchor {
        START, MIDDLE, END
    }

    public record Text(double x, double y, double size, Anchor anchor, boolean bold, boolean monospace, String content, Style style) implements Shape {
    }

    /** Path segments, all in absolute coordinates. */
    public sealed interface Segment permits MoveTo, LineTo, CubicTo, QuadTo, ArcTo, Close {
    }

    public record MoveTo(double x, double y) implements Segment {
    }

    public record LineTo(double x, double y) implements Segment {
    }

    public record CubicTo(double x1, double y1, double x2, double y2, double x, double y) implements Segment {
    }

    public record QuadTo(double x1, double y1, double x, double y) implements Segment {
    }

    public record ArcTo(double rx, double ry, double rotation, boolean largeArc, boolean sweep, double x, double y) implements Segment {
    }

    public record Close() implements Segment {
    }

    /** Why an input was refused: reason is one word (element, attribute, value, number, ...), for tests and the port. */
    static final class Refused extends Exception {
        private final String reason;

        Refused(String reason, String detail) {
            super(reason + ": " + detail, null, false, false);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

    /** The drawing, or empty when anything in the input is outside the subset (show a placeholder). Never throws. */
    public static Optional<Drawing> parse(String svg) {
        try {
            return Optional.of(read(svg));
        } catch(Refused | RuntimeException e) {
            //A RuntimeException would be a bug; the tests run read() and fail on one, the app gets a placeholder
            return Optional.empty();
        }
    }

    static Drawing read(String svg) throws Refused {
        if(svg == null || svg.length() > MAX_CHARS) {
            throw new Refused("length", "missing or longer than " + MAX_CHARS + " characters");
        }
        return new Reader(svg).document();
    }

    // ---------------- The XML reader ----------------

    //transforms is immutable and shared down the tree; composed is their product, kept only to bound it
    private record State(int fill, int stroke, double strokeWidth, double fillOpacity, double strokeOpacity, double opacity,
                         List<double[]> transforms, double[] composed, boolean monospace, double fontSize, boolean bold, Anchor anchor) {
        Style style() {
            return new Style(paint(fill, fillOpacity), paint(stroke, strokeOpacity), strokeWidth, opacity, transforms, composed);
        }

        private static Paint paint(int rgb, double opacity) {
            return rgb == NONE ? null : new Paint(((int)Math.round(opacity * 255) << 24) | rgb);
        }
    }

    private static final class Frame {
        final String name;
        final State state;
        final StringBuilder text;
        final double x;
        final double y;

        Frame(String name, State state, boolean holdsText, double x, double y) {
            this.name = name;
            this.state = state;
            this.text = holdsText ? new StringBuilder() : null;
            this.x = x;
            this.y = y;
        }
    }

    private static final class Reader {
        private final String s;
        private final int n;
        private int pos;
        private int elements;
        private boolean rootDone;
        private double width;
        private double height;
        private final ArrayDeque<Frame> stack = new ArrayDeque<>();
        private final List<Shape> shapes = new ArrayList<>();

        Reader(String s) {
            this.s = s;
            this.n = s.length();
        }

        Drawing document() throws Refused {
            checkCharacters();
            if(s.startsWith("<?xml") && n > 5 && isSpace(s.charAt(5))) {
                declaration();
            }
            while(pos < n) {
                if(s.charAt(pos) != '<') {
                    characterData();
                } else if(s.startsWith("<!--", pos)) {
                    comment();
                } else if(s.startsWith("<![CDATA[", pos)) {
                    throw new Refused("cdata", "CDATA section");
                } else if(s.startsWith("<!", pos)) {
                    throw new Refused("doctype", "DOCTYPE or other declaration");
                } else if(s.startsWith("<?", pos)) {
                    throw new Refused("pi", "processing instruction");
                } else if(s.startsWith("</", pos)) {
                    endTag();
                } else if(rootDone) {
                    throw new Refused("content", "an element after the root");
                } else {
                    startTag();
                }
            }
            if(!rootDone) {
                throw stack.isEmpty() ? new Refused("content", "no root element") : new Refused("syntax", "unclosed element");
            }
            return new Drawing(width, height, shapes);
        }

        private void checkCharacters() throws Refused {
            for(int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if(c < 0x20) {
                    if(c != '\t' && c != '\n' && c != '\r') {
                        throw new Refused("char", "control character");
                    }
                } else if(Character.isHighSurrogate(c)) {
                    if(i + 1 >= n || !Character.isLowSurrogate(s.charAt(i + 1))) {
                        throw new Refused("char", "unpaired surrogate");
                    }
                    i++;
                } else if(Character.isLowSurrogate(c) || c == 0xFFFE || c == 0xFFFF) {
                    throw new Refused("char", "not an XML character");
                }
            }
        }

        private void declaration() throws Refused {
            int end = s.indexOf("?>");
            if(end < 0) {
                throw new Refused("syntax", "unterminated XML declaration");
            }
            String[] fields = {"version", "encoding", "standalone"};
            int i = 5;
            int field = 0;
            while(true) {
                int spaces = i;
                while(i < end && isSpace(s.charAt(i))) {
                    i++;
                }
                if(i == end) {
                    break;
                }
                int found = -1;
                for(int f = field; f < fields.length && i > spaces; f++) {
                    if(s.startsWith(fields[f] + "=", i)) {
                        found = f;
                        break;
                    }
                }
                if(found < 0 || (field == 0 && found != 0)) {
                    throw new Refused("pi", "malformed XML declaration");
                }
                i += fields[found].length() + 1;
                char quote = i < end ? s.charAt(i) : 0;
                int close = quote == '"' || quote == '\'' ? s.indexOf(quote, i + 1) : -1;
                if(close < 0 || close > end || close == i + 1) {
                    throw new Refused("pi", "malformed XML declaration");
                }
                for(int k = i + 1; k < close; k++) {
                    char c = s.charAt(k);
                    if(!isAsciiLetterOrDigit(c) && c != '.' && c != '_' && c != '-') {
                        throw new Refused("pi", "malformed XML declaration");
                    }
                }
                if(found == 1 && !asciiLower(s.substring(i + 1, close)).equals("utf-8")) {
                    throw new Refused("pi", "an encoding other than UTF-8");
                }
                i = close + 1;
                field = found + 1;
            }
            if(field == 0) {
                throw new Refused("pi", "XML declaration without a version");
            }
            pos = end + 2;
        }

        private void comment() throws Refused {
            int dashes = s.indexOf("--", pos + 4);
            if(dashes < 0 || !s.startsWith("-->", dashes)) {
                throw new Refused("syntax", "unterminated comment or \"--\" inside one");
            }
            pos = dashes + 3;
        }

        private void characterData() throws Refused {
            int end = s.indexOf('<', pos);
            if(end < 0) {
                end = n;
            }
            Frame top = stack.peek();
            if(top == null || top.text == null) {
                for(int i = pos; i < end; i++) {
                    if(!isSpace(s.charAt(i))) {
                        throw new Refused("content", "text outside a text element");
                    }
                }
                pos = end;
                return;
            }
            while(pos < end) {
                char c = s.charAt(pos);
                if(c == '>' && pos >= 2 && s.charAt(pos - 1) == ']' && s.charAt(pos - 2) == ']') {
                    throw new Refused("syntax", "\"]]>\" in text");
                }
                if(c == '&') {
                    pos = entity(pos, top.text);
                } else {
                    top.text.append(c);
                    pos++;
                }
            }
        }

        /** Decodes the reference starting with the '&' at index at into out, and returns the index after its ';'. */
        private int entity(int at, StringBuilder out) throws Refused {
            int semicolon = -1;
            for(int i = at + 1; i < n && i < at + 12; i++) {
                if(s.charAt(i) == ';') {
                    semicolon = i;
                    break;
                }
            }
            if(semicolon < 0) {
                throw new Refused("entity", "unterminated or unknown reference");
            }
            String name = s.substring(at + 1, semicolon);
            switch(name) {
                case "lt" -> out.append('<');
                case "gt" -> out.append('>');
                case "amp" -> out.append('&');
                case "quot" -> out.append('"');
                case "apos" -> out.append('\'');
                default -> out.appendCodePoint(characterReference(name));
            }
            return semicolon + 1;
        }

        private static int characterReference(String name) throws Refused {
            boolean hex = name.startsWith("#x");
            String digits = hex ? name.substring(2) : name.startsWith("#") ? name.substring(1) : "";
            if(digits.isEmpty() || digits.length() > (hex ? 6 : 7)) {
                throw new Refused("entity", "unknown entity");
            }
            int code = 0;
            for(int i = 0; i < digits.length(); i++) {
                int digit = hex ? hexDigit(digits.charAt(i)) : (digits.charAt(i) >= '0' && digits.charAt(i) <= '9' ? digits.charAt(i) - '0' : -1);
                if(digit < 0) {
                    throw new Refused("entity", "malformed character reference");
                }
                code = code * (hex ? 16 : 10) + digit;
            }
            boolean legal = code == 0x9 || code == 0xA || code == 0xD || (code >= 0x20 && code <= 0xD7FF) || (code >= 0xE000 && code <= 0xFFFD)
                    || (code >= 0x10000 && code <= 0x10FFFF);
            if(!legal) {
                throw new Refused("entity", "character reference to an illegal character");
            }
            return code;
        }

        private String name() throws Refused {
            int start = pos;
            if(pos < n && (isAsciiLetter(s.charAt(pos)) || s.charAt(pos) == '_')) {
                pos++;
                while(pos < n && (isAsciiLetterOrDigit(s.charAt(pos)) || ".-_:".indexOf(s.charAt(pos)) >= 0)) {
                    pos++;
                }
            }
            if(pos == start || pos - start > MAX_NAME_CHARS) {
                throw new Refused("syntax", "malformed name");
            }
            return s.substring(start, pos);
        }

        private void skipSpaces() {
            while(pos < n && isSpace(s.charAt(pos))) {
                pos++;
            }
        }

        private void startTag() throws Refused {
            pos++;
            String name = name();
            Frame parent = stack.peek();
            if(name.indexOf(':') >= 0) {
                throw new Refused("namespace", "prefixed element " + name);
            }
            if(!ATTRIBUTES.containsKey(name)) {
                throw new Refused("element", name);
            }
            boolean allowed;
            if(parent == null) {
                allowed = name.equals("svg");
            } else if(parent.name.equals("svg") || parent.name.equals("g")) {
                allowed = !name.equals("svg");
            } else if(SHAPES.contains(parent.name)) {
                allowed = name.equals("title") || name.equals("desc");
            } else {
                allowed = false;
            }
            if(!allowed) {
                throw new Refused("element", name + (parent == null ? " as the root" : " inside " + parent.name));
            }
            if(++elements > MAX_ELEMENTS) {
                throw new Refused("elements", "more than " + MAX_ELEMENTS + " elements");
            }
            if(stack.size() >= MAX_DEPTH) {
                throw new Refused("nesting", "nested deeper than " + MAX_DEPTH);
            }

            Map<String, String> attributes = new LinkedHashMap<>();
            boolean selfClosing;
            while(true) {
                int before = pos;
                skipSpaces();
                if(pos >= n) {
                    throw new Refused("syntax", "unterminated tag");
                }
                char c = s.charAt(pos);
                if(c == '>') {
                    pos++;
                    selfClosing = false;
                    break;
                }
                if(c == '/') {
                    if(pos + 1 < n && s.charAt(pos + 1) == '>') {
                        pos += 2;
                        selfClosing = true;
                        break;
                    }
                    throw new Refused("syntax", "malformed tag end");
                }
                if(pos == before) {
                    throw new Refused("syntax", "no space before an attribute");
                }
                String attribute = name();
                checkAttributeName(name, attribute, parent == null);
                skipSpaces();
                if(pos >= n || s.charAt(pos) != '=') {
                    throw new Refused("syntax", "attribute without a value");
                }
                pos++;
                skipSpaces();
                String value = attributeValue();
                if(attributes.put(attribute, value) != null) {
                    throw new Refused("syntax", "duplicate attribute " + attribute);
                }
            }

            element(name, attributes, parent);
            if(selfClosing) {
                close();
            }
        }

        private static void checkAttributeName(String element, String attribute, boolean root) throws Refused {
            if(attribute.equals("xmlns") || attribute.startsWith("xmlns:")) {
                if(!root || !(attribute.equals("xmlns") || attribute.equals("xmlns:xlink"))) {
                    throw new Refused("namespace", attribute);
                }
                return;
            }
            if(attribute.indexOf(':') >= 0) {
                throw new Refused("namespace", attribute);
            }
            if(!ATTRIBUTES.get(element).contains(attribute)) {
                throw new Refused("attribute", attribute + " on " + element);
            }
        }

        private String attributeValue() throws Refused {
            if(pos >= n || (s.charAt(pos) != '"' && s.charAt(pos) != '\'')) {
                throw new Refused("syntax", "unquoted attribute value");
            }
            char quote = s.charAt(pos++);
            StringBuilder value = new StringBuilder();
            while(true) {
                if(pos >= n) {
                    throw new Refused("syntax", "unterminated attribute value");
                }
                char c = s.charAt(pos);
                if(c == quote) {
                    pos++;
                    return value.toString();
                } else if(c == '<') {
                    throw new Refused("syntax", "'<' in an attribute value");
                } else if(c == '&') {
                    pos = entity(pos, value);
                } else {
                    value.append(isSpace(c) ? ' ' : c);
                    pos++;
                }
            }
        }

        private void endTag() throws Refused {
            pos += 2;
            String name = name();
            skipSpaces();
            if(pos >= n || s.charAt(pos) != '>') {
                throw new Refused("syntax", "malformed end tag");
            }
            pos++;
            Frame top = stack.peek();
            if(top == null || !top.name.equals(name)) {
                throw new Refused("syntax", "mismatched end tag " + name);
            }
            close();
        }

        private void element(String name, Map<String, String> attributes, Frame parent) throws Refused {
            //Every decoded value, read or ignored, is checked before anything else looks at it
            for(String value : attributes.values()) {
                String lower = asciiLower(value);
                if(lower.contains("url(") || lower.contains("javascript:")) {
                    throw new Refused("value", "a reference or a script in an attribute value");
                }
            }
            for(Map.Entry<String, String> entry : attributes.entrySet()) {
                if(entry.getKey().equals("xmlns") && !entry.getValue().equals(SVG_NAMESPACE)
                        || entry.getKey().equals("xmlns:xlink") && !entry.getValue().equals(XLINK_NAMESPACE)) {
                    throw new Refused("namespace", entry.getKey() + "=" + entry.getValue());
                }
            }
            if(parent == null && !attributes.containsKey("xmlns")) {
                throw new Refused("namespace", "the root does not declare the SVG namespace");
            }

            State state = inherit(parent == null ? root(attributes) : parent.state, attributes);
            Style style = state.style();
            double x = 0;
            double y = 0;
            switch(name) {
                case "rect" -> {
                    double w = length(attributes, "width", true);
                    double h = length(attributes, "height", true);
                    Double rx = attributes.containsKey("rx") ? length(attributes, "rx", true) : null;
                    Double ry = attributes.containsKey("ry") ? length(attributes, "ry", true) : null;
                    double rxUsed = rx != null ? rx : ry != null ? ry : 0;
                    double ryUsed = ry != null ? ry : rx != null ? rx : 0;
                    shapes.add(new Rect(length(attributes, "x", false), length(attributes, "y", false), w, h,
                            Math.min(rxUsed, w / 2), Math.min(ryUsed, h / 2), style));
                }
                case "circle" -> shapes.add(new Circle(length(attributes, "cx", false), length(attributes, "cy", false),
                        length(attributes, "r", true), style));
                case "ellipse" -> shapes.add(new Ellipse(length(attributes, "cx", false), length(attributes, "cy", false),
                        length(attributes, "rx", true), length(attributes, "ry", true), style));
                case "line" -> shapes.add(new Line(length(attributes, "x1", false), length(attributes, "y1", false),
                        length(attributes, "x2", false), length(attributes, "y2", false), style));
                case "polyline", "polygon" -> {
                    String points = attributes.get("points");
                    if(points == null) {
                        throw new Refused("points", "no points");
                    }
                    shapes.add(new Polyline(points(points), name.equals("polygon"), style));
                }
                case "path" -> {
                    String d = attributes.get("d");
                    if(d == null) {
                        throw new Refused("path", "no path data");
                    }
                    shapes.add(new PathShape(path(d), style));
                }
                case "text" -> {
                    x = length(attributes, "x", false);
                    y = length(attributes, "y", false);
                }
                default -> {
                }
            }
            boolean holdsText = name.equals("text") || name.equals("title") || name.equals("desc");
            stack.push(new Frame(name, state, holdsText, x, y));
        }

        private void close() throws Refused {
            Frame frame = stack.pop();
            if(frame.name.equals("text")) {
                State state = frame.state;
                shapes.add(new Text(frame.x, frame.y, state.fontSize(), state.anchor(), state.bold(), state.monospace(),
                        clean(frame.text), state.style()));
            }
            if(stack.isEmpty()) {
                rootDone = true;
            }
        }

        /** The root's size, and the state its attributes start from. */
        private State root(Map<String, String> attributes) throws Refused {
            Double w = attributes.containsKey("width") ? side(attributes.get("width")) : null;
            Double h = attributes.containsKey("height") ? side(attributes.get("height")) : null;
            List<double[]> transforms = List.of();
            double[] composed = {1, 0, 0, 1, 0, 0};
            String viewBox = attributes.get("viewBox");
            if(viewBox != null) {
                Scan scan = new Scan(viewBox);
                scan.spaces();
                double[] box = new double[4];
                for(int i = 0; i < 4; i++) {
                    if(i > 0) {
                        scan.separator();
                    }
                    box[i] = scan.number("size");
                }
                scan.spaces();
                if(!scan.end() || !(box[2] >= MIN_SIDE && box[2] <= MAX_SIDE && box[3] >= MIN_SIDE && box[3] <= MAX_SIDE)) {
                    throw new Refused("size", "viewBox");
                }
                width = box[2];
                height = box[3];
                if(box[0] != 0 || box[1] != 0) {
                    composed = new double[] {1, 0, 0, 1, -box[0], -box[1]};
                    transforms = List.of(composed.clone());
                }
            } else if(w != null && h != null) {
                width = w;
                height = h;
            } else {
                throw new Refused("size", "no viewBox, width and height");
            }
            return new State(0x000000, NONE, 1, 1, 1, 1, transforms, composed, false, 16, false, Anchor.START);
        }

        private static double side(String value) throws Refused {
            double side = length(value, "size");
            if(!(side >= MIN_SIDE && side <= MAX_SIDE)) {
                throw new Refused("size", "width or height out of range");
            }
            return side;
        }
    }

    // ---------------- Attribute values ----------------

    private static Set<String> attributes(boolean paint, boolean font, String... own) {
        List<String> names = new ArrayList<>(List.of(own));
        names.add("id");
        if(paint) {
            names.addAll(List.of("fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity"));
        }
        if(font) {
            names.addAll(List.of("font-family", "font-size", "font-weight", "text-anchor"));
        }
        return Set.copyOf(names);
    }

    private static State inherit(State parent, Map<String, String> attributes) throws Refused {
        int fill = parent.fill();
        int stroke = parent.stroke();
        double strokeWidth = parent.strokeWidth();
        double fillOpacity = parent.fillOpacity();
        double strokeOpacity = parent.strokeOpacity();
        double opacity = parent.opacity();
        List<double[]> transforms = parent.transforms();
        double[] composed = parent.composed();
        boolean monospace = parent.monospace();
        double fontSize = parent.fontSize();
        boolean bold = parent.bold();
        Anchor anchor = parent.anchor();
        for(Map.Entry<String, String> entry : attributes.entrySet()) {
            String value = entry.getValue();
            switch(entry.getKey()) {
                case "fill" -> fill = colour(value);
                case "stroke" -> stroke = colour(value);
                case "stroke-width" -> strokeWidth = nonNegative(length(value, "number"));
                case "fill-opacity" -> fillOpacity = unit(value);
                case "stroke-opacity" -> strokeOpacity = unit(value);
                case "opacity" -> opacity = parent.opacity() * unit(value);
                case "transform" -> {
                    List<double[]> own = transforms(value);
                    for(double[] transform : own) {
                        composed = compose(composed, transform);
                    }
                    List<double[]> all = new ArrayList<>(transforms);
                    all.addAll(own);
                    transforms = List.copyOf(all);
                }
                case "font-family" -> monospace = family(value);
                case "font-size" -> {
                    fontSize = length(value, "number");
                    if(!(fontSize > 0 && fontSize <= MAX_FONT_SIZE)) {
                        throw new Refused("font", "font size " + value);
                    }
                }
                case "font-weight" -> bold = weight(value);
                case "text-anchor" -> anchor = anchor(value);
                case "shape-rendering" -> {
                    if(!SHAPE_RENDERING.contains(asciiLower(trim(value)))) {
                        throw new Refused("attribute", "shape-rendering " + value);
                    }
                }
                case "version" -> {
                    if(!VERSIONS.contains(trim(value))) {
                        throw new Refused("attribute", "version " + value);
                    }
                }
                default -> {
                    //Geometry is read by the element; id, xmlns and xmlns:xlink are ignored once checked
                }
            }
        }
        return new State(fill, stroke, strokeWidth, fillOpacity, strokeOpacity, opacity, transforms, composed, monospace, fontSize, bold, anchor);
    }

    /** p followed by t (p x t), each entry evaluated left to right; every entry must stay finite and within MAX_COMPOSED. */
    private static double[] compose(double[] p, double[] t) throws Refused {
        double[] m = {
                p[0] * t[0] + p[2] * t[1],
                p[1] * t[0] + p[3] * t[1],
                p[0] * t[2] + p[2] * t[3],
                p[1] * t[2] + p[3] * t[3],
                p[0] * t[4] + p[2] * t[5] + p[4],
                p[1] * t[4] + p[3] * t[5] + p[5]};
        for(double entry : m) {
            if(!(Math.abs(entry) <= MAX_COMPOSED)) {
                throw new Refused("transform", "the transforms multiply beyond " + MAX_COMPOSED);
            }
        }
        return m;
    }

    /** A geometry attribute (0 when absent): a number, optionally in px. */
    private static double length(Map<String, String> attributes, String name, boolean nonNegative) throws Refused {
        String value = attributes.get(name);
        if(value == null) {
            return 0;
        }
        double length = length(value, "number");
        return nonNegative ? nonNegative(length) : length;
    }

    private static double length(String value, String reason) throws Refused {
        Scan scan = new Scan(value);
        scan.spaces();
        double number = scan.number(reason);
        if(scan.v.startsWith("px", scan.i)) {
            scan.i += 2;
        }
        scan.spaces();
        if(!scan.end()) {
            throw new Refused(reason, "not a plain length: " + value);
        }
        return number;
    }

    private static double nonNegative(double value) throws Refused {
        if(value < 0) {
            throw new Refused("number", "negative");
        }
        return value;
    }

    /** An opacity: a plain number, clamped to 0..1. */
    private static double unit(String value) throws Refused {
        Scan scan = new Scan(value);
        scan.spaces();
        double number = scan.number("number");
        scan.spaces();
        if(!scan.end()) {
            throw new Refused("number", "not a plain number: " + value);
        }
        return Math.max(0, Math.min(1, number));
    }

    private static int colour(String value) throws Refused {
        String v = asciiLower(trim(value));
        if(v.equals("none")) {
            return NONE;
        }
        if(v.startsWith("#") && (v.length() == 4 || v.length() == 7)) {
            int rgb = 0;
            for(int i = 1; i < v.length(); i++) {
                int digit = hexDigit(v.charAt(i));
                if(digit < 0) {
                    throw new Refused("colour", value);
                }
                rgb = v.length() == 4 ? (rgb << 8) | (digit << 4) | digit : (rgb << 4) | digit;
            }
            return rgb;
        }
        if(v.startsWith("rgb(") && v.endsWith(")")) {
            String inner = v.substring(4, v.length() - 1);
            int rgb = 0;
            int start = 0;
            for(int part = 0; part < 3; part++) {
                int comma = inner.indexOf(',', start);
                if((part < 2) != (comma >= 0)) {
                    throw new Refused("colour", value);
                }
                String channel = trim(inner.substring(start, part < 2 ? comma : inner.length()));
                if(channel.isEmpty() || channel.length() > 3) {
                    throw new Refused("colour", value);
                }
                int level = 0;
                for(int i = 0; i < channel.length(); i++) {
                    char c = channel.charAt(i);
                    if(c < '0' || c > '9') {
                        throw new Refused("colour", value);
                    }
                    level = level * 10 + (c - '0');
                }
                if(level > 255) {
                    throw new Refused("colour", value);
                }
                rgb = (rgb << 8) | level;
                start = comma + 1;
            }
            return rgb;
        }
        Integer named = COLOUR_NAMES.get(v);
        if(named == null) {
            throw new Refused("colour", value);
        }
        return named;
    }

    private static List<double[]> transforms(String value) throws Refused {
        Scan scan = new Scan(value);
        List<double[]> transforms = new ArrayList<>();
        scan.spaces();
        while(!scan.end()) {
            if(transforms.size() == MAX_TRANSFORMS) {
                throw new Refused("transform", "more than " + MAX_TRANSFORMS + " transforms");
            }
            int start = scan.i;
            while(!scan.end() && isAsciiLetter(scan.peek())) {
                scan.i++;
            }
            String function = scan.v.substring(start, scan.i);
            scan.spaces();
            if(scan.end() || scan.peek() != '(') {
                throw new Refused("transform", "malformed transform");
            }
            scan.i++;
            scan.spaces();
            double[] arguments = new double[6];
            int count = 0;
            boolean afterComma = false;
            while(true) {
                if(scan.end()) {
                    throw new Refused("transform", "unclosed transform");
                }
                if(scan.peek() == ')' && !afterComma) {
                    scan.i++;
                    break;
                }
                if(count == arguments.length) {
                    throw new Refused("transform", "too many arguments");
                }
                arguments[count++] = scan.number("transform");
                afterComma = scan.separator();
            }
            transforms.add(matrix(function, arguments, count));
            if(scan.separator() && scan.end()) {
                throw new Refused("transform", "trailing comma");
            }
        }
        return transforms;
    }

    private static double[] matrix(String function, double[] a, int count) throws Refused {
        switch(function) {
            case "translate" -> {
                if(count == 1 || count == 2) {
                    return new double[] {1, 0, 0, 1, a[0], count == 2 ? a[1] : 0};
                }
            }
            case "scale" -> {
                if(count == 1 || count == 2) {
                    return new double[] {a[0], 0, 0, count == 2 ? a[1] : a[0], 0, 0};
                }
            }
            case "rotate" -> {
                if(count == 1 || count == 3) {
                    double turn = a[0] % 360;
                    if(turn < 0) {
                        turn += 360;
                    }
                    double cos;
                    double sin;
                    if(turn == 0) {
                        cos = 1;
                        sin = 0;
                    } else if(turn == 90) {
                        cos = 0;
                        sin = 1;
                    } else if(turn == 180) {
                        cos = -1;
                        sin = 0;
                    } else if(turn == 270) {
                        cos = 0;
                        sin = -1;
                    } else {
                        double radians = turn / 180.0 * Math.PI;
                        cos = StrictMath.cos(radians);
                        sin = StrictMath.sin(radians);
                    }
                    double cx = count == 3 ? a[1] : 0;
                    double cy = count == 3 ? a[2] : 0;
                    return new double[] {cos, sin, -sin, cos, cx - cos * cx + sin * cy, cy - sin * cx - cos * cy};
                }
            }
            case "matrix" -> {
                if(count == 6) {
                    return a.clone();
                }
            }
            default -> throw new Refused("transform", "unknown transform " + function);
        }
        throw new Refused("transform", "wrong number of arguments to " + function);
    }

    private static double[] points(String value) throws Refused {
        Scan scan = new Scan(value);
        double[] numbers = new double[16];
        int count = 0;
        boolean afterComma = false;
        scan.spaces();
        while(!scan.end()) {
            if(count == 2 * MAX_POINTS) {
                throw new Refused("points", "more than " + MAX_POINTS + " points");
            }
            if(count == numbers.length) {
                numbers = Arrays.copyOf(numbers, Math.min(numbers.length * 2, 2 * MAX_POINTS));
            }
            numbers[count++] = scan.number("points");
            afterComma = scan.separator();
        }
        if(afterComma || count == 0 || count % 2 != 0) {
            throw new Refused("points", "not a list of points");
        }
        return Arrays.copyOf(numbers, count);
    }

    private static List<Segment> path(String d) throws Refused {
        Scan scan = new Scan(d);
        scan.spaces();
        if(scan.end() || (scan.peek() != 'M' && scan.peek() != 'm')) {
            throw new Refused("path", "path data must start with a moveto");
        }
        List<Segment> segments = new ArrayList<>();
        double x = 0;
        double y = 0;
        double startX = 0;
        double startY = 0;
        double controlX = 0;
        double controlY = 0;
        char previous = 0;
        char command = 0;
        boolean afterComma = false;
        while(true) {
            if(scan.end()) {
                if(afterComma) {
                    throw new Refused("path", "trailing comma");
                }
                return segments;
            }
            char c = scan.peek();
            if("MmLlHhVvCcSsQqTtAaZz".indexOf(c) >= 0) {
                if(afterComma) {
                    throw new Refused("path", "comma before a command");
                }
                command = c;
                scan.i++;
                scan.spaces();
                if(c == 'Z' || c == 'z') {
                    add(segments, new Close());
                    x = startX;
                    y = startY;
                    previous = 'Z';
                    continue;
                }
            } else if(command == 0 || command == 'Z' || command == 'z' || !scan.numberNext()) {
                throw new Refused("path", "unexpected '" + c + "' in path data");
            } else if(command == 'M') {
                command = 'L';
            } else if(command == 'm') {
                command = 'l';
            }

            boolean relative = command >= 'a';
            double dx = relative ? x : 0;
            double dy = relative ? y : 0;
            switch(Character.toUpperCase(command)) {
                case 'M' -> {
                    x = coordinate(dx + scan.number("path"));
                    y = coordinate(dy + scan.argument());
                    startX = x;
                    startY = y;
                    add(segments, new MoveTo(x, y));
                }
                case 'L' -> {
                    x = coordinate(dx + scan.number("path"));
                    y = coordinate(dy + scan.argument());
                    add(segments, new LineTo(x, y));
                }
                case 'H' -> {
                    x = coordinate(dx + scan.number("path"));
                    add(segments, new LineTo(x, y));
                }
                case 'V' -> {
                    y = coordinate(dy + scan.number("path"));
                    add(segments, new LineTo(x, y));
                }
                case 'C' -> {
                    double x1 = coordinate(dx + scan.number("path"));
                    double y1 = coordinate(dy + scan.argument());
                    controlX = coordinate(dx + scan.argument());
                    controlY = coordinate(dy + scan.argument());
                    x = coordinate(dx + scan.argument());
                    y = coordinate(dy + scan.argument());
                    add(segments, new CubicTo(x1, y1, controlX, controlY, x, y));
                }
                case 'S' -> {
                    double x1 = coordinate(previous == 'C' ? 2 * x - controlX : x);
                    double y1 = coordinate(previous == 'C' ? 2 * y - controlY : y);
                    controlX = coordinate(dx + scan.number("path"));
                    controlY = coordinate(dy + scan.argument());
                    x = coordinate(dx + scan.argument());
                    y = coordinate(dy + scan.argument());
                    add(segments, new CubicTo(x1, y1, controlX, controlY, x, y));
                }
                case 'Q' -> {
                    controlX = coordinate(dx + scan.number("path"));
                    controlY = coordinate(dy + scan.argument());
                    x = coordinate(dx + scan.argument());
                    y = coordinate(dy + scan.argument());
                    add(segments, new QuadTo(controlX, controlY, x, y));
                }
                case 'T' -> {
                    controlX = coordinate(previous == 'Q' ? 2 * x - controlX : x);
                    controlY = coordinate(previous == 'Q' ? 2 * y - controlY : y);
                    x = coordinate(dx + scan.number("path"));
                    y = coordinate(dy + scan.argument());
                    add(segments, new QuadTo(controlX, controlY, x, y));
                }
                default -> {
                    //'A'
                    double rx = scan.number("path");
                    double ry = scan.argument();
                    if(rx < 0 || ry < 0) {
                        throw new Refused("path", "negative arc radius");
                    }
                    double rotation = scan.argument();
                    boolean largeArc = scan.flag();
                    boolean sweep = scan.flag();
                    x = coordinate(dx + scan.argument());
                    y = coordinate(dy + scan.argument());
                    add(segments, new ArcTo(rx, ry, rotation, largeArc, sweep, x, y));
                }
            }
            char upper = Character.toUpperCase(command);
            previous = upper == 'C' || upper == 'S' ? 'C' : upper == 'Q' || upper == 'T' ? 'Q' : upper;
            afterComma = scan.separator();
        }
    }

    private static void add(List<Segment> segments, Segment segment) throws Refused {
        if(segments.size() == MAX_PATH_SEGMENTS) {
            throw new Refused("path", "more than " + MAX_PATH_SEGMENTS + " segments");
        }
        segments.add(segment);
    }

    private static double coordinate(double value) throws Refused {
        if(!(Math.abs(value) <= MAX_MAGNITUDE)) {
            throw new Refused("number", "a coordinate out of range");
        }
        return value;
    }

    private static boolean family(String value) throws Refused {
        if(value.indexOf('"') >= 0 || value.indexOf('\'') >= 0) {
            throw new Refused("font", "quoted font family");
        }
        boolean monospace = false;
        int start = 0;
        for(int index = 0; start <= value.length(); index++) {
            int comma = value.indexOf(',', start);
            int end = comma < 0 ? value.length() : comma;
            //Trimmed, with each run of whitespace inside it read as one space
            StringBuilder name = new StringBuilder();
            boolean gap = false;
            for(int i = start; i < end; i++) {
                char c = value.charAt(i);
                if(isSpace(c)) {
                    gap = name.length() > 0;
                } else {
                    if(gap) {
                        name.append(' ');
                        gap = false;
                    }
                    name.append(c);
                }
            }
            String family = asciiLower(name.toString());
            if(!MONOSPACE_FAMILIES.contains(family) && !OTHER_FAMILIES.contains(family)) {
                throw new Refused("font", "font family " + family);
            }
            if(index == 0) {
                monospace = MONOSPACE_FAMILIES.contains(family);
            }
            start = end + 1;
        }
        return monospace;
    }

    private static boolean weight(String value) throws Refused {
        String v = asciiLower(trim(value));
        if(v.equals("normal")) {
            return false;
        }
        if(v.equals("bold")) {
            return true;
        }
        if(!v.isEmpty() && v.length() <= 4) {
            int weight = 0;
            for(int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if(c < '0' || c > '9') {
                    throw new Refused("font", "font weight " + value);
                }
                weight = weight * 10 + (c - '0');
            }
            if(weight >= 1 && weight <= 1000) {
                return weight >= 600;
            }
        }
        throw new Refused("font", "font weight " + value);
    }

    private static Anchor anchor(String value) throws Refused {
        return switch(asciiLower(trim(value))) {
            case "start" -> Anchor.START;
            case "middle" -> Anchor.MIDDLE;
            case "end" -> Anchor.END;
            default -> throw new Refused("font", "text anchor " + value);
        };
    }

    /** Text content: whitespace collapsed and trimmed, control, format and separator characters removed, at most 200 code points. */
    static String clean(CharSequence raw) throws Refused {
        StringBuilder clean = new StringBuilder();
        boolean space = false;
        int count = 0;
        for(int i = 0; i < raw.length(); ) {
            int c = Character.codePointAt(raw, i);
            i += Character.charCount(c);
            if(c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                space = clean.length() > 0;
                continue;
            }
            if(TextRules.stripped(c)) {
                continue;
            }
            if(space) {
                clean.append(' ');
                count++;
                space = false;
            }
            clean.appendCodePoint(c);
            if(++count > MAX_TEXT) {
                throw new Refused("text", "more than " + MAX_TEXT + " characters of text");
            }
        }
        return clean.toString();
    }

    // ---------------- Small helpers ----------------

    /** A cursor over one attribute value. */
    private static final class Scan {
        final String v;
        int i;

        Scan(String v) {
            this.v = v;
        }

        boolean end() {
            return i >= v.length();
        }

        char peek() {
            return v.charAt(i);
        }

        void spaces() {
            while(i < v.length() && isSpace(v.charAt(i))) {
                i++;
            }
        }

        /** Skips whitespace and at most one comma; true when a comma was skipped. */
        boolean separator() {
            spaces();
            if(i < v.length() && v.charAt(i) == ',') {
                i++;
                spaces();
                return true;
            }
            return false;
        }

        boolean numberNext() {
            if(end()) {
                return false;
            }
            char c = peek();
            return (c >= '0' && c <= '9') || c == '.' || c == '+' || c == '-';
        }

        /** The next path argument after a separator. */
        double argument() throws Refused {
            separator();
            return number("path");
        }

        boolean flag() throws Refused {
            separator();
            if(!end() && (peek() == '0' || peek() == '1')) {
                return v.charAt(i++) == '1';
            }
            throw new Refused("path", "an arc flag must be 0 or 1");
        }

        /** A number; refuses with missing when none starts here, and with "number" when one is malformed or out of range. */
        double number(String missing) throws Refused {
            int start = i;
            if(!numberNext()) {
                throw new Refused(missing, "expected a number");
            }
            if(peek() == '+' || peek() == '-') {
                i++;
            }
            int digits = 0;
            while(!end() && peek() >= '0' && peek() <= '9') {
                i++;
                digits++;
            }
            if(!end() && peek() == '.') {
                i++;
                while(!end() && peek() >= '0' && peek() <= '9') {
                    i++;
                    digits++;
                }
            }
            if(digits == 0) {
                throw new Refused("number", "a sign or a point without digits");
            }
            if(!end() && (peek() == 'e' || peek() == 'E')) {
                int e = i + 1;
                if(e < v.length() && (v.charAt(e) == '+' || v.charAt(e) == '-')) {
                    e++;
                }
                int exponentStart = e;
                while(e < v.length() && v.charAt(e) >= '0' && v.charAt(e) <= '9') {
                    e++;
                }
                if(e - exponentStart > 3) {
                    throw new Refused("number", "an exponent of more than 3 digits");
                }
                if(e > exponentStart) {
                    i = e;
                }
            }
            if(i - start > MAX_NUMBER_CHARS) {
                throw new Refused("number", "a number longer than " + MAX_NUMBER_CHARS + " characters");
            }
            double value = Double.parseDouble(v.substring(start, i));
            if(!(Math.abs(value) <= MAX_MAGNITUDE)) {
                throw new Refused("number", "out of range");
            }
            return value;
        }
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }

    private static int hexDigit(char c) {
        if(c >= '0' && c <= '9') {
            return c - '0';
        }
        if(c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if(c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static String trim(String value) {
        int start = 0;
        int end = value.length();
        while(start < end && isSpace(value.charAt(start))) {
            start++;
        }
        while(end > start && isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static String asciiLower(String value) {
        StringBuilder lower = new StringBuilder(value.length());
        for(int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char)(c + 32) : c);
        }
        return lower.toString();
    }

    // ---------------- The stable text form ----------------

    /** The drawing in the stable text form described above, one line per entry. */
    public static List<String> describe(Drawing drawing) {
        List<String> lines = new ArrayList<>();
        lines.add("size " + number(drawing.width()) + " " + number(drawing.height()));
        for(Shape shape : drawing.shapes()) {
            StringBuilder line = new StringBuilder();
            switch(shape) {
                case Rect r -> line.append("rect x=").append(number(r.x())).append(" y=").append(number(r.y())).append(" w=").append(number(r.width()))
                        .append(" h=").append(number(r.height())).append(" rx=").append(number(r.rx())).append(" ry=").append(number(r.ry()));
                case Circle c -> line.append("circle cx=").append(number(c.cx())).append(" cy=").append(number(c.cy())).append(" r=").append(number(c.r()));
                case Ellipse e -> line.append("ellipse cx=").append(number(e.cx())).append(" cy=").append(number(e.cy())).append(" rx=").append(number(e.rx()))
                        .append(" ry=").append(number(e.ry()));
                case Line l -> line.append("line x1=").append(number(l.x1())).append(" y1=").append(number(l.y1())).append(" x2=").append(number(l.x2()))
                        .append(" y2=").append(number(l.y2()));
                case Polyline p -> {
                    line.append(p.closed() ? "polygon points=[" : "polyline points=[");
                    double[] points = p.points();
                    for(int i = 0; i < points.length; i += 2) {
                        line.append(i == 0 ? "" : ";").append(number(points[i])).append(' ').append(number(points[i + 1]));
                    }
                    line.append(']');
                }
                case PathShape p -> {
                    line.append("path d=[");
                    for(int i = 0; i < p.segments().size(); i++) {
                        line.append(i == 0 ? "" : ";").append(segment(p.segments().get(i)));
                    }
                    line.append(']');
                }
                case Text t -> line.append("text x=").append(number(t.x())).append(" y=").append(number(t.y())).append(" size=").append(number(t.size()))
                        .append(" anchor=").append(t.anchor() == Anchor.START ? "start" : t.anchor() == Anchor.MIDDLE ? "middle" : "end")
                        .append(" bold=").append(t.bold()).append(" mono=").append(t.monospace());
            }
            line.append(" fill=").append(paint(shape.fill())).append(" stroke=").append(paint(shape.stroke()))
                    .append(" sw=").append(number(shape.strokeWidth())).append(" op=").append(number(shape.opacity())).append(" m=[");
            double[] matrix = shape.composedMatrix();
            for(int k = 0; k < 6; k++) {
                line.append(k == 0 ? "" : " ").append(number(matrix[k]));
            }
            line.append(']');
            if(shape instanceof Text t) {
                line.append(" content=").append(t.content());
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static String segment(Segment segment) {
        return switch(segment) {
            case MoveTo m -> "M " + number(m.x()) + " " + number(m.y());
            case LineTo l -> "L " + number(l.x()) + " " + number(l.y());
            case CubicTo c -> "C " + number(c.x1()) + " " + number(c.y1()) + " " + number(c.x2()) + " " + number(c.y2()) + " " + number(c.x()) + " " + number(c.y());
            case QuadTo q -> "Q " + number(q.x1()) + " " + number(q.y1()) + " " + number(q.x()) + " " + number(q.y());
            case ArcTo a -> "A " + number(a.rx()) + " " + number(a.ry()) + " " + number(a.rotation()) + " " + (a.largeArc() ? "1" : "0") + " "
                    + (a.sweep() ? "1" : "0") + " " + number(a.x()) + " " + number(a.y());
            case Close c -> "Z";
        };
    }

    private static String paint(Paint paint) {
        if(paint == null) {
            return "none";
        }
        String digits = "0123456789ABCDEF";
        StringBuilder hex = new StringBuilder("#");
        for(int shift = 28; shift >= 0; shift -= 4) {
            hex.append(digits.charAt((paint.argb() >>> shift) & 0xF));
        }
        return hex.toString();
    }

    /** A number in the stable form: the exact binary value rounded half-even to 6 decimals, trailing zeros removed, "0" for zero. */
    static String number(double value) {
        BigDecimal rounded = new BigDecimal(value).setScale(6, RoundingMode.HALF_EVEN);
        return rounded.signum() == 0 ? "0" : rounded.stripTrailingZeros().toPlainString();
    }
}
