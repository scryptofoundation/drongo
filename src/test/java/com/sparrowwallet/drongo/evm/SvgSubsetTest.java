package com.sparrowwallet.drongo.evm;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;
import java.util.stream.Stream;

public class SvgSubsetTest {
    private static final String RESOURCES = "/com/sparrowwallet/drongo/evm/svg/";
    private static final String SOURCE = "src/main/java/com/sparrowwallet/drongo/evm/SvgSubset.java";
    private static final String NS = "xmlns=\"http://www.w3.org/2000/svg\"";
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    private static String resource(String name) {
        try(InputStream in = SvgSubsetTest.class.getResourceAsStream(RESOURCES + name)) {
            Assertions.assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch(IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> vectorNames(String folder) {
        try {
            Path dir = Path.of(SvgSubsetTest.class.getResource(RESOURCES + folder).toURI());
            try(Stream<Path> files = Files.list(dir)) {
                return files.map(path -> path.getFileName().toString()).sorted().toList();
            }
        } catch(IOException | URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> allVectors() {
        List<String> vectors = new ArrayList<>();
        vectorNames("accept").forEach(name -> vectors.add(resource("accept/" + name)));
        vectorNames("refuse").forEach(name -> vectors.add(resource("refuse/" + name)));
        return vectors;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String name) {
        return (Map<String, Object>)MiniJson.parse(resource(name));
    }

    /** The reason a refusal gives, or null when the input is accepted; any other exception fails the test. */
    private static String refusal(String svg) {
        try {
            SvgSubset.read(svg);
            return null;
        } catch(SvgSubset.Refused e) {
            return e.reason();
        }
    }

    /** Reads svg; an accepted drawing must be sane, a refusal is fine, any other exception fails the test. */
    private static void check(String svg) {
        try {
            assertSane(SvgSubset.read(svg));
        } catch(SvgSubset.Refused e) {
            Assertions.assertNotNull(e.reason());
        }
    }

    private static SvgSubset.Drawing drawing(String svg) {
        try {
            return SvgSubset.read(svg);
        } catch(SvgSubset.Refused e) {
            throw new AssertionError("refused (" + e.getMessage() + ")");
        }
    }

    private static List<String> described(String svg) {
        return SvgSubset.describe(drawing(svg));
    }

    private static void within(double value, double low, double high, String what) {
        Assertions.assertTrue(Double.isFinite(value) && value >= low && value <= high, what + " " + value);
    }

    private static void coordinate(double value) {
        within(value, -SvgSubset.MAX_MAGNITUDE, SvgSubset.MAX_MAGNITUDE, "coordinate");
    }

    /** Every number in an accepted drawing is finite and within the limits the reader promises. */
    private static void assertSane(SvgSubset.Drawing drawing) {
        within(drawing.width(), SvgSubset.MIN_SIDE, SvgSubset.MAX_SIDE, "width");
        within(drawing.height(), SvgSubset.MIN_SIDE, SvgSubset.MAX_SIDE, "height");
        Assertions.assertTrue(drawing.shapes().size() < SvgSubset.MAX_ELEMENTS);
        for(SvgSubset.Shape shape : drawing.shapes()) {
            double[] matrix = shape.composedMatrix();
            Assertions.assertEquals(6, matrix.length);
            for(double entry : matrix) {
                within(entry, -SvgSubset.MAX_COMPOSED, SvgSubset.MAX_COMPOSED, "matrix entry");
            }
            within(shape.strokeWidth(), 0, SvgSubset.MAX_MAGNITUDE, "stroke width");
            within(shape.opacity(), 0, 1, "opacity");
            Assertions.assertTrue(shape.transforms().size() <= SvgSubset.MAX_TRANSFORMS * SvgSubset.MAX_DEPTH + 1);
            switch(shape) {
                case SvgSubset.Rect r -> {
                    coordinate(r.x());
                    coordinate(r.y());
                    within(r.width(), 0, SvgSubset.MAX_MAGNITUDE, "rect width");
                    within(r.height(), 0, SvgSubset.MAX_MAGNITUDE, "rect height");
                    within(r.rx(), 0, r.width() / 2, "rx");
                    within(r.ry(), 0, r.height() / 2, "ry");
                }
                case SvgSubset.Circle c -> {
                    coordinate(c.cx());
                    coordinate(c.cy());
                    within(c.r(), 0, SvgSubset.MAX_MAGNITUDE, "r");
                }
                case SvgSubset.Ellipse e -> {
                    coordinate(e.cx());
                    coordinate(e.cy());
                    within(e.rx(), 0, SvgSubset.MAX_MAGNITUDE, "rx");
                    within(e.ry(), 0, SvgSubset.MAX_MAGNITUDE, "ry");
                }
                case SvgSubset.Line l -> {
                    coordinate(l.x1());
                    coordinate(l.y1());
                    coordinate(l.x2());
                    coordinate(l.y2());
                }
                case SvgSubset.Polyline p -> {
                    double[] points = p.points();
                    Assertions.assertTrue(points.length >= 2 && points.length % 2 == 0 && points.length <= 2 * SvgSubset.MAX_POINTS);
                    for(double point : points) {
                        coordinate(point);
                    }
                }
                case SvgSubset.PathShape p -> {
                    Assertions.assertTrue(!p.segments().isEmpty() && p.segments().size() <= SvgSubset.MAX_PATH_SEGMENTS);
                    Assertions.assertInstanceOf(SvgSubset.MoveTo.class, p.segments().get(0));
                    for(SvgSubset.Segment segment : p.segments()) {
                        switch(segment) {
                            case SvgSubset.MoveTo m -> { coordinate(m.x()); coordinate(m.y()); }
                            case SvgSubset.LineTo l -> { coordinate(l.x()); coordinate(l.y()); }
                            case SvgSubset.CubicTo c -> { coordinate(c.x1()); coordinate(c.y1()); coordinate(c.x2()); coordinate(c.y2()); coordinate(c.x()); coordinate(c.y()); }
                            case SvgSubset.QuadTo q -> { coordinate(q.x1()); coordinate(q.y1()); coordinate(q.x()); coordinate(q.y()); }
                            case SvgSubset.ArcTo a -> {
                                within(a.rx(), 0, SvgSubset.MAX_MAGNITUDE, "arc rx");
                                within(a.ry(), 0, SvgSubset.MAX_MAGNITUDE, "arc ry");
                                coordinate(a.rotation());
                                coordinate(a.x());
                                coordinate(a.y());
                            }
                            case SvgSubset.Close c -> { }
                        }
                    }
                }
                case SvgSubset.Text t -> {
                    coordinate(t.x());
                    coordinate(t.y());
                    Assertions.assertTrue(t.size() > 0 && t.size() <= SvgSubset.MAX_FONT_SIZE, "font size " + t.size());
                    String content = t.content();
                    Assertions.assertTrue(content.codePointCount(0, content.length()) <= SvgSubset.MAX_TEXT);
                    Assertions.assertFalse(content.startsWith(" ") || content.endsWith(" ") || content.contains("  "), content);
                    Assertions.assertTrue(content.codePoints().noneMatch(c -> TextRules.stripped(c) || c == '\t' || c == '\n' || c == '\r'
                            || Character.getType(c) == Character.SURROGATE), content);
                }
            }
        }
    }

    /** The composition the reader documents, done again here: p followed by t, left to right. */
    private static double[] compose(double[] p, double[] t) {
        return new double[] {p[0] * t[0] + p[2] * t[1], p[1] * t[0] + p[3] * t[1], p[0] * t[2] + p[2] * t[3], p[1] * t[2] + p[3] * t[3],
                p[0] * t[4] + p[2] * t[5] + p[4], p[1] * t[4] + p[3] * t[5] + p[5]};
    }

    @Test
    public void acceptVectorsGiveTheExpectedDrawing() {
        Map<String, Object> expected = json("expected.json");
        List<String> names = vectorNames("accept");
        Assertions.assertEquals(new TreeSet<>(expected.keySet()), new TreeSet<>(names), "expected.json lists exactly the accept vectors");
        Assertions.assertTrue(names.size() >= 23);
        for(String name : names) {
            String svg = resource("accept/" + name);
            Assertions.assertEquals(expected.get(name), described(svg), name);
            Assertions.assertTrue(SvgSubset.parse(svg).isPresent(), name);
            assertSane(drawing(svg));
        }
    }

    @Test
    public void refuseVectorsAreAllRefused() {
        Map<String, Object> reasons = json("refusals.json");
        List<String> names = vectorNames("refuse");
        Assertions.assertEquals(new TreeSet<>(reasons.keySet()), new TreeSet<>(names), "refusals.json lists exactly the refuse vectors");
        Assertions.assertTrue(names.size() >= 146);
        for(String name : names) {
            String svg = resource("refuse/" + name);
            Assertions.assertEquals(reasons.get(name), refusal(svg), name);
            Assertions.assertEquals(Optional.empty(), SvgSubset.parse(svg), name);
        }
    }

    @Test
    public void nothingThrows() {
        long started = System.nanoTime();
        List<String> vectors = allVectors();

        //Every prefix of every vector up to 20,000 characters; for longer ones the first and last 1,000 and every 997th
        for(String vector : vectors) {
            int length = vector.length();
            for(int i = 0; i <= length; i++) {
                if(length <= 20_000 || i < 1_000 || i > length - 1_000 || i % 997 == 0) {
                    check(vector.substring(0, i));
                }
            }
        }

        //Hostile strings that are not vectors
        String lone = String.valueOf((char)0xD800);
        String loneLow = String.valueOf((char)0xDC00);
        String open = "<svg " + NS + " viewBox=\"0 0 1 1\">";
        List<String> hostile = new ArrayList<>(List.of("", "\u0000", "<svg>\u0000</svg>", lone, open + loneLow + "</svg>",
                open + "<text>" + lone + "</text></svg>", "<".repeat(1_000_000), "<".repeat(100_000), "&".repeat(100_000),
                open + "<g>".repeat(30_000), open + "<path d=\"M" + "9".repeat(99_000) + "\"/></svg>",
                open + "<text>&#" + "9".repeat(50_000) + ";</text></svg>", open + "<text>&" + "a".repeat(50_000) + ";</text></svg>",
                open + "<path d=\"M0 0" + " 1e".repeat(20_000) + "\"/></svg>", "<svg " + "a=\"\" ".repeat(10_000) + "/>",
                open + "<!--" + "-".repeat(50_000) + "></svg>", open + "<rect transform=\"" + "rotate(".repeat(10_000) + "\"/></svg>",
                "<" + "a".repeat(99_000) + "/>", open + "<rect " + "x".repeat(99_000) + "=\"1\"/></svg>",
                open + "<text>" + "]]".repeat(49_000) + "</text></svg>", open + "<text>" + "a<!---->".repeat(12_000) + "</text></svg>",
                open + "<polyline points=\"" + "1,".repeat(49_000) + "\"/></svg>"));
        hostile.add(null);
        for(String text : hostile) {
            Assertions.assertDoesNotThrow(() -> refusal(text));
            Assertions.assertDoesNotThrow(() -> SvgSubset.parse(text));
        }

        //The single worst inputs each read in well under 2 seconds
        List<String> large = new ArrayList<>(hostile.stream().filter(text -> text != null && text.length() >= 50_000).toList());
        large.addAll(vectors.stream().filter(vector -> vector.length() >= 50_000).toList());
        Assertions.assertTrue(large.size() >= 10);
        for(String text : large) {
            long start = System.nanoTime();
            check(text);
            long millis = (System.nanoTime() - start) / 1_000_000;
            Assertions.assertTrue(millis < 2_000, "a " + text.length() + " character input took " + millis + " ms");
        }

        //Random edits of every vector, with characters that matter to the reader; what is accepted must be sane
        Random random = new Random(20261111);
        String alphabet = "<>/=\"'&#;!?-[]:x0123456789.eE+-, \t\nMmLlZzAaHhVvCcSsQqTt()\u0000" + lone + loneLow + "\u202E";
        for(String vector : vectors) {
            if(vector.isEmpty()) {
                continue;
            }
            int rounds = vector.length() <= 5_000 ? 300 : 10;
            for(int round = 0; round < rounds; round++) {
                StringBuilder edited = new StringBuilder(vector);
                for(int edit = 0; edit < 1 + random.nextInt(3); edit++) {
                    int at = random.nextInt(edited.length() + 1);
                    char c = alphabet.charAt(random.nextInt(alphabet.length()));
                    switch(random.nextInt(3)) {
                        case 0 -> edited.insert(at, c);
                        case 1 -> { if(at < edited.length()) edited.deleteCharAt(at); }
                        default -> { if(at < edited.length()) edited.setCharAt(at, c); }
                    }
                }
                check(edited.toString());
            }
        }

        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        Assertions.assertTrue(seconds < 120, "nothingThrows took " + seconds + " s");
    }

    @Test
    public void theVaultBadgeDraws() {
        //The accept vector is exactly the picture the contract returns for World 1, as recorded from the chain
        NftMetadata metadata = NftMetadata.parse(Erc721Test.resource("vault-badge-world-1-token-uri.txt")).orElseThrow();
        String svg = ((NftMetadata.Image.Svg)metadata.image()).source();
        Assertions.assertEquals(svg, resource("accept/vault-badge-world-1.svg"));
        Assertions.assertTrue(svg.startsWith("<svg " + NS + " "), "the badge declares the SVG namespace");

        SvgSubset.Drawing drawing = SvgSubset.parse(svg).orElseThrow();
        Assertions.assertEquals(64, drawing.width());
        Assertions.assertEquals(64, drawing.height());
        Assertions.assertEquals(7, drawing.shapes().size());
        Assertions.assertEquals(3, drawing.shapes().stream().filter(shape -> shape instanceof SvgSubset.Rect).count());
        Assertions.assertEquals(2, drawing.shapes().stream().filter(shape -> shape instanceof SvgSubset.Circle).count());
        SvgSubset.PathShape path = Assertions.assertInstanceOf(SvgSubset.PathShape.class, drawing.shapes().get(5));
        Assertions.assertEquals(16, path.segments().size());
        Assertions.assertEquals(new SvgSubset.MoveTo(30, 16), path.segments().get(0));
        Assertions.assertEquals(new SvgSubset.Close(), path.segments().get(15));
        SvgSubset.Text text = Assertions.assertInstanceOf(SvgSubset.Text.class, drawing.shapes().get(6));
        Assertions.assertEquals("WORLD 1", text.content());
        Assertions.assertEquals(SvgSubset.Anchor.MIDDLE, text.anchor());
        Assertions.assertTrue(text.bold());
        Assertions.assertTrue(text.monospace());
        Assertions.assertEquals(9, text.size());
        Assertions.assertEquals(new SvgSubset.Paint(0xFFFFFFFF), text.fill());
        Assertions.assertNull(text.stroke());
        Assertions.assertEquals(new SvgSubset.Paint(0xFF0E1E38), drawing.shapes().get(0).fill());
        for(SvgSubset.Shape shape : drawing.shapes()) {
            Assertions.assertArrayEquals(new double[] {1, 0, 0, 1, 0, 0}, shape.composedMatrix());
        }

        //Every World draws the same way
        Assertions.assertEquals("WORLD 4", ((SvgSubset.Text)SvgSubset.parse(svg.replace("WORLD 1", "WORLD 4")).orElseThrow().shapes().get(6)).content());
    }

    @Test
    public void theModelCannotBeChanged() {
        SvgSubset.Drawing drawing = drawing("<svg " + NS + " viewBox=\"0 0 10 10\"><g transform=\"translate(1 2)\"><polygon points=\"1 2 3 4\"/></g></svg>");
        SvgSubset.Polyline polygon = (SvgSubset.Polyline)drawing.shapes().get(0);
        polygon.points()[0] = 99;
        polygon.transforms().get(0)[4] = 99;
        polygon.composedMatrix()[4] = 99;
        Assertions.assertEquals(1, polygon.points()[0]);
        Assertions.assertEquals(1, polygon.transforms().get(0)[4]);
        Assertions.assertArrayEquals(new double[] {1, 0, 0, 1, 1, 2}, polygon.composedMatrix());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> drawing.shapes().clear());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> polygon.transforms().clear());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> polygon.transforms().set(0, new double[6]));
    }

    @Test
    public void composedMatricesAreFiniteAndBounded() {
        //Every accept vector
        for(String name : vectorNames("accept")) {
            assertSane(drawing(resource("accept/" + name)));
        }

        //The composed matrix is the documented product of the list, outermost first, and the list is shared, not copied
        String open = "<svg " + NS + " viewBox=\"-3 4 10 10\">";
        String nested = open + ("<g transform=\"rotate(33 5 5) scale(1.5 0.5) translate(-2 7) matrix(0.5 1 -1 0.5 3 4)\">").repeat(8)
                + "<rect width=\"1\" height=\"1\" transform=\"rotate(-71)\"/><circle r=\"1\"/>" + "</g>".repeat(8) + "</svg>";
        SvgSubset.Drawing drawing = drawing(nested);
        assertSane(drawing);
        SvgSubset.Shape rect = drawing.shapes().get(0);
        double[] product = {1, 0, 0, 1, 0, 0};
        for(double[] transform : rect.transforms()) {
            product = compose(product, transform);
        }
        Assertions.assertArrayEquals(product, rect.composedMatrix());
        Assertions.assertEquals(34, rect.transforms().size());
        Assertions.assertSame(drawing.shapes().get(1).transforms(), drawing.shapes().get(1).transforms());

        //Nested cases: accepted ones stay within the bound, the rest refuse as a transform or a number
        String rect1 = "<rect width=\"1\" height=\"1\"/>";
        List<String> cases = List.of(
                open + ("<g transform=\"" + "translate(1000000) ".repeat(16) + "\">").repeat(30) + rect1 + "</g>".repeat(30) + "</svg>",
                open + ("<g transform=\"" + "rotate(7 1000000 -1000000) ".repeat(16) + "\">").repeat(30) + rect1 + "</g>".repeat(30) + "</svg>",
                open + ("<g transform=\"" + "scale(1000000) ".repeat(16) + "\">").repeat(2) + ("<g transform=\"" + "scale(0.000001) ".repeat(16) + "\">").repeat(2)
                        + rect1 + "</g>".repeat(4) + "</svg>",
                open + ("<g transform=\"" + "scale(0.000001) ".repeat(16) + "\">").repeat(18) + ("<g transform=\"" + "scale(1000000) ".repeat(16) + "\">").repeat(12)
                        + rect1 + "</g>".repeat(30) + "</svg>",
                open + ("<g transform=\"" + "matrix(1000000 1000000 1000000 1000000 1000000 1000000) ".repeat(2) + "\">").repeat(2) + rect1 + "</g>".repeat(2) + "</svg>",
                open + ("<g transform=\"" + "matrix(0 1 -1 0 1000000 -1000000) ".repeat(16) + "\">").repeat(30) + rect1 + "</g>".repeat(30) + "</svg>",
                open + ("<g transform=\"" + "scale(-31622.7766) ".repeat(2) + "\">").repeat(1) + rect1 + "</g></svg>");
        int accepted = 0;
        for(String svg : cases) {
            String reason = refusal(svg);
            if(reason == null) {
                assertSane(drawing(svg));
                accepted++;
            } else {
                Assertions.assertTrue(reason.equals("transform") || reason.equals("number"), reason);
            }
        }
        Assertions.assertTrue(accepted >= 3, "accepted " + accepted);

        //The exact bound, the viewBox translation included
        Assertions.assertArrayEquals(new double[] {1000, 0, 0, 1000, 1_000_000_000, 0},
                drawing(resource("accept/limit-composed-1e9.svg")).shapes().get(0).composedMatrix());
        Assertions.assertEquals("transform", refusal(resource("refuse/transform-composed-just-over.svg")));
    }

    @Test
    public void limitsAreExact() {
        String open = "<svg " + NS + " viewBox=\"0 0 10 10\">";
        String close = "</svg>";

        //Elements, the root counted
        Assertions.assertNull(refusal(open + "<g/>".repeat(1_999) + close));
        Assertions.assertEquals("elements", refusal(open + "<g/>".repeat(2_000) + close));

        //Nesting, the root and the innermost element counted
        Assertions.assertNull(refusal(open + "<g>".repeat(30) + "<rect width=\"1\" height=\"1\"/>" + "</g>".repeat(30) + close));
        Assertions.assertEquals("nesting", refusal(open + "<g>".repeat(31) + "<rect width=\"1\" height=\"1\"/>" + "</g>".repeat(31) + close));

        //Size, at least 1 and at most 4,096 per side
        Assertions.assertNull(refusal("<svg " + NS + " viewBox=\"0 0 4096 4096\"/>"));
        Assertions.assertNull(refusal("<svg " + NS + " width=\"4096px\" height=\"4096\"/>"));
        Assertions.assertEquals("size", refusal("<svg " + NS + " viewBox=\"0 0 4096.001 1\"/>"));
        Assertions.assertEquals("size", refusal("<svg " + NS + " width=\"1\" height=\"4096.001\"/>"));
        Assertions.assertNull(refusal("<svg " + NS + " viewBox=\"0 0 1 1\"/>"));
        Assertions.assertNull(refusal("<svg " + NS + " width=\"1\" height=\"1\"/>"));
        Assertions.assertEquals("size", refusal("<svg " + NS + " viewBox=\"0 0 0.999999 1\"/>"));
        Assertions.assertEquals("size", refusal("<svg " + NS + " viewBox=\"0 0 1 1e-320\"/>"));
        Assertions.assertEquals("size", refusal("<svg " + NS + " width=\"0.999999\" height=\"1\"/>"));
        Assertions.assertEquals("size", refusal("<svg " + NS + " viewBox=\"0 0 1 1\" width=\"0.5\" height=\"1\"/>"));

        //The root must declare the SVG namespace; the declaration may name only UTF-8
        Assertions.assertEquals("namespace", refusal("<svg viewBox=\"0 0 1 1\"/>"));
        Assertions.assertNull(refusal("<?xml version=\"1.0\"?><svg " + NS + " viewBox=\"0 0 1 1\"/>"));
        Assertions.assertNull(refusal("<?xml version=\"1.0\" encoding=\"utf-8\"?><svg " + NS + " viewBox=\"0 0 1 1\"/>"));
        Assertions.assertNull(refusal("<?xml version=\"1.0\" encoding='UTF-8' standalone=\"yes\"?><svg " + NS + " viewBox=\"0 0 1 1\"/>"));
        for(String encoding : List.of("UTF8", "utf-16", "ISO-8859-1", "US-ASCII", "UTF-8-x")) {
            Assertions.assertEquals("pi", refusal("<?xml version=\"1.0\" encoding=\"" + encoding + "\"?><svg " + NS + " viewBox=\"0 0 1 1\"/>"), encoding);
        }

        //Font size above 0 and at most 4,096
        Assertions.assertNull(refusal(open + "<text font-size=\"4096\">a</text>" + close));
        Assertions.assertNull(refusal(open + "<text font-size=\"0.001\">a</text>" + close));
        Assertions.assertEquals("font", refusal(open + "<text font-size=\"4096.001\">a</text>" + close));
        Assertions.assertEquals("font", refusal(open + "<text font-size=\"0\">a</text>" + close));
        Assertions.assertEquals("font", refusal(open + "<g font-size=\"-0\"/>" + close));

        //Path segments, the moveto counted
        Assertions.assertNull(refusal(open + "<path d=\"M0 0" + "h0".repeat(9_999) + "\"/>" + close));
        Assertions.assertEquals("path", refusal(open + "<path d=\"M0 0" + "h0".repeat(10_000) + "\"/>" + close));

        //Points
        Assertions.assertNull(refusal(open + "<polyline points=\"" + "1 2 ".repeat(10_000) + "\"/>" + close));
        Assertions.assertEquals("points", refusal(open + "<polyline points=\"" + "1 2 ".repeat(10_001) + "\"/>" + close));

        //Transforms on one element
        Assertions.assertNull(refusal(open + "<g transform=\"" + "scale(1) ".repeat(16) + "\"/>" + close));
        Assertions.assertEquals("transform", refusal(open + "<g transform=\"" + "scale(1) ".repeat(17) + "\"/>" + close));

        //The product of a shape's transforms, its ancestors' and the viewBox translation included
        Assertions.assertNull(refusal(open + "<g transform=\"scale(1000) scale(1000)\"><rect transform=\"scale(1000)\" width=\"1\" height=\"1\"/></g>" + close));
        Assertions.assertEquals("transform", refusal(open + "<g transform=\"scale(1000) scale(1000)\"><rect transform=\"scale(1000.001)\" width=\"1\" height=\"1\"/></g>" + close));
        Assertions.assertNull(refusal("<svg " + NS + " viewBox=\"-1000 0 1 1\"><g transform=\"scale(1000)\"><rect transform=\"translate(999999)\"/></g></svg>"));
        Assertions.assertEquals("transform", refusal("<svg " + NS + " viewBox=\"-1000 0 1 1\"><g transform=\"scale(1000)\"><rect transform=\"translate(1000000)\"/></g></svg>"));
        Assertions.assertEquals("transform", refusal(open + ("<g transform=\"" + "scale(1000000) ".repeat(16) + "\">").repeat(30) + "<rect width=\"1\" height=\"1\"/>"
                + "</g>".repeat(30) + close));

        //A long transform chain is shared between the shapes of a group, not copied into each
        String chained = open + ("<g transform=\"" + "scale(1) ".repeat(16) + "\">").repeat(30) + "<line/>".repeat(1_969) + "</g>".repeat(30) + close;
        SvgSubset.Drawing many = SvgSubset.parse(chained).orElseThrow();
        Assertions.assertEquals(1_969, many.shapes().size());
        Assertions.assertEquals(480, many.shapes().get(1_968).transforms().size());

        //Number magnitude
        Assertions.assertNull(refusal(open + "<rect x=\"1000000\" y=\"-1e6\" width=\"1\" height=\"1\"/>" + close));
        Assertions.assertEquals("number", refusal(open + "<rect x=\"1000000.000001\" width=\"1\" height=\"1\"/>" + close));
        Assertions.assertEquals("number", refusal(open + "<rect y=\"-1000001\" width=\"1\" height=\"1\"/>" + close));

        //Text length in code points, after sanitising: an emoji is one character, a stripped zero-width space none
        Assertions.assertNull(refusal(open + "<text>" + EMOJI.repeat(200) + "\u200B</text>" + close));
        Assertions.assertEquals("text", refusal(open + "<text>" + EMOJI.repeat(201) + "</text>" + close));
        List<String> lines = described(open + "<text>" + "x".repeat(199) + EMOJI + "</text>" + close);
        Assertions.assertTrue(lines.get(1).endsWith("content=" + "x".repeat(199) + EMOJI), lines.get(1));

        //Characters, counted before anything else
        String head = "<svg " + NS + " viewBox=\"0 0 1 1\"><!-- ";
        String tail = " --></svg>";
        String fits = head + "a".repeat(SvgSubset.MAX_CHARS - head.length() - tail.length()) + tail;
        Assertions.assertEquals(100_000, fits.length());
        Assertions.assertNull(refusal(fits));
        Assertions.assertEquals("length", refusal(head + "a".repeat(SvgSubset.MAX_CHARS + 1 - head.length() - tail.length()) + tail));
        Assertions.assertEquals("length", refusal(null));
    }

    @Test
    public void numbersPrintOneWay() {
        Assertions.assertEquals("0", SvgSubset.number(0));
        Assertions.assertEquals("0", SvgSubset.number(-0.0));
        Assertions.assertEquals("0", SvgSubset.number(-1e-17));
        Assertions.assertEquals("0.3", SvgSubset.number(0.1 + 0.2));
        Assertions.assertEquals("100", SvgSubset.number(100));
        Assertions.assertEquals("-1000000", SvgSubset.number(-1e6));
        Assertions.assertEquals("1000000000", SvgSubset.number(1e9));
        Assertions.assertEquals("0.000001", SvgSubset.number(1e-6));
        Assertions.assertEquals("0", SvgSubset.number(4e-7));
        Assertions.assertEquals("0.707107", SvgSubset.number(Math.sqrt(0.5)));
        Assertions.assertEquals("12.5", SvgSubset.number(12.5));
    }

    @Test
    public void textIsSanitised() {
        String open = "<svg " + NS + " viewBox=\"0 0 10 10\"><text>";
        String close = "</text></svg>";
        //Bidirectional overrides and isolates, zero-width characters, line separators and C1 controls are removed;
        //tab, line feed and carriage return become spaces; runs of spaces collapse; the ends are trimmed
        String hostile = "\u202E A\u200B\u2066B\u2028C\u0085\u009BD\r\n\tE&#x202D;&#10;F &#160; ";
        List<String> lines = described(open + hostile + close);
        Assertions.assertTrue(lines.get(1).endsWith("content=ABCD E F \u00A0"), lines.get(1));
        String content = ((SvgSubset.Text)SvgSubset.parse(open + hostile + close).orElseThrow().shapes().get(0)).content();
        Assertions.assertEquals("ABCD E F \u00A0", content);
        Assertions.assertTrue(content.codePoints().allMatch(c -> c >= 0x20 && !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT
                && Character.getType(c) != Character.LINE_SEPARATOR && Character.getType(c) != Character.SURROGATE), content);
        //The strip table itself is TextRules' (TextRulesTest)
    }

    @Test
    public void tripwireForXmlLibrariesRenderingAndNetworkCode() throws IOException {
        //A tripwire, not a proof: the class files name none of these, and the source imports only java.util, java.math and java.lang
        for(Class<?> member : SvgSubset.class.getNestMembers()) {
            String classFile = member.getName().replace('.', '/') + ".class";
            try(InputStream in = member.getClassLoader().getResourceAsStream(classFile)) {
                String constants = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                for(String forbidden : List.of("javax/xml", "org/w3c", "org/xml", "java/awt", "javafx", "java/net/", "java/nio/", "java/io/File",
                        "java/util/regex", "LitVmRpcClient", "HttpClient", "Socket", "URL")) {
                    Assertions.assertFalse(constants.contains(forbidden), member.getName() + " refers to " + forbidden);
                }
            }
        }

        String source = Files.readString(Path.of(SOURCE), StandardCharsets.UTF_8);
        List<String> imports = source.lines().filter(line -> line.startsWith("import ")).toList();
        Assertions.assertFalse(imports.isEmpty());
        for(String line : imports) {
            Assertions.assertTrue((line.startsWith("import java.util.") || line.startsWith("import java.math.") || line.startsWith("import java.lang."))
                    && !line.contains("regex") && !line.contains("concurrent") && !line.contains("stream"), line);
        }
        for(String forbidden : List.of(".split(", ".replaceAll(", ".replaceFirst(", ".matches(", "java.util.regex", "Pattern.", "Matcher", "javax.",
                "javafx", "java.awt", "java.net", "java.nio", "java.io", "Thread", "Runtime.getRuntime", "ProcessBuilder", "Class.forName")) {
            Assertions.assertFalse(source.contains(forbidden), "SvgSubset.java uses " + forbidden);
        }
    }
}
