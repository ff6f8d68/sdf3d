package mods.hexagon.sdf3d.editor;

import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.shape.SVGPath;

/**
 * Crisp monochrome icons for the toolbar. Each path is drawn in a 24-unit viewbox; {@link #line}
 * renders an outlined icon and {@link #fill} a solid one, both themed to match the dark CSS.
 */
public final class Icons {
    private static final Color FILL = Color.web("#e8e9ec");
    private static final Color STROKE = Color.web("#d7dae0");

    public static final String NEW = "M6 3 H13 L17 7 V21 H6 Z M11 9 V15 M8 12 H14";
    public static final String OPEN = "M3 6 H9 L11 8 H21 V20 H3 Z";
    public static final String SAVE = "M5 3 H16 L19 6 V21 H5 Z M8 3 V9 H15 V3 M8 21 V13 H15 V21";
    public static final String EXPORT = "M12 3 V15 M8 11 L12 15 L16 11 M4 19 H20";
    public static final String UNDO = "M9 6 L4 12 L9 18 L9 13 H15 A5 5 0 0 1 20 18";
    public static final String REDO = "M15 6 L20 12 L15 18 L15 13 H9 A5 5 0 0 0 4 18";
    public static final String GROUP = "M4 4 H11 V11 H4 Z M13 13 H20 V20 H13 Z";
    public static final String DUPLICATE = "M9 5 H19 V15 H9 Z M5 9 H15 V19 H5 Z";
    public static final String IMAGE = "M3 5 H21 V19 H3 Z M8 15 L11 11 L14 15 L16 13 L20 15";
    public static final String UV = "M3 3 H21 V21 H3 Z M3 9 H21 M3 15 H21 M9 3 V21 M15 3 V21";
    public static final String FRAME = "M12 3 V8 M12 16 V21 M3 12 H8 M16 12 H21 M12 12 m-6 0 a6 6 0 1 0 12 0 a6 6 0 1 0 -12 0";
    public static final String PLAY = "M9 6 L19 12 L9 18 Z";
    public static final String PAUSE = "M8 5 H11 V19 H8 Z M13 5 H16 V19 H13 Z";

    public static Node line(String d) {
        SVGPath p = new SVGPath();
        p.setContent(d);
        p.setStroke(STROKE);
        p.setStrokeWidth(1.8);
        p.setStrokeLineCap(StrokeLineCap.ROUND);
        p.setStrokeLineJoin(StrokeLineJoin.ROUND);
        p.setFill(Color.TRANSPARENT);
        return p;
    }

    public static Node fill(String d) {
        SVGPath p = new SVGPath();
        p.setContent(d);
        p.setFill(FILL);
        return p;
    }

    private Icons() {}
}
