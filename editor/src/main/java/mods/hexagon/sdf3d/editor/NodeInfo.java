package mods.hexagon.sdf3d.editor;

import mods.hexagon.sdf3d.sdf.SdfGraph;

/** Maps {@link SdfGraph} opcodes to human-readable names for the editor's node tree. */
public final class NodeInfo {
    private NodeInfo() {}

    public static String opName(int op) {
        return switch (op) {
            case SdfGraph.CONST -> "const";
            case SdfGraph.X -> "x";
            case SdfGraph.Y -> "y";
            case SdfGraph.Z -> "z";
            case SdfGraph.ADD -> "add";
            case SdfGraph.SUB -> "sub";
            case SdfGraph.MUL -> "mul";
            case SdfGraph.DIV -> "div";
            case SdfGraph.NEG -> "neg";
            case SdfGraph.ABS -> "abs";
            case SdfGraph.MIN -> "min";
            case SdfGraph.MAX -> "max";
            case SdfGraph.SPHERE -> "sphere";
            case SdfGraph.BOX -> "box";
            case SdfGraph.ROUNDED_BOX -> "rounded_box";
            case SdfGraph.ELLIPSOID -> "ellipsoid";
            case SdfGraph.TORUS -> "torus";
            case SdfGraph.CYLINDER -> "cylinder";
            case SdfGraph.CAPSULE -> "capsule";
            case SdfGraph.PLANE -> "plane";
            case SdfGraph.CONE -> "cone";
            case SdfGraph.HEX_PRISM -> "hex_prism";
            case SdfGraph.OCTAHEDRON -> "octahedron";
            case SdfGraph.UNION -> "union";
            case SdfGraph.INTERSECT -> "intersect";
            case SdfGraph.SUBTRACT -> "subtract";
            case SdfGraph.SMOOTH_UNION -> "smooth_union";
            case SdfGraph.SMOOTH_INTERSECT -> "smooth_intersect";
            case SdfGraph.SMOOTH_SUBTRACT -> "smooth_subtract";
            case SdfGraph.TRANSLATE -> "translate";
            case SdfGraph.SCALE -> "scale";
            case SdfGraph.STRETCH -> "stretch";
            case SdfGraph.ROTATE -> "rotate";
            case SdfGraph.ROUND -> "round";
            case SdfGraph.ONION -> "onion";
            case SdfGraph.SPIN -> "spin";
            case SdfGraph.BOB -> "bob";
            case SdfGraph.PULSE -> "pulse";
            case SdfGraph.MORPH -> "morph";
            case SdfGraph.TWIST -> "twist";
            case SdfGraph.BEND -> "bend";
            case SdfGraph.KEYFRAME_SCALE -> "keyframe_scale";
            case SdfGraph.KEYFRAME_TRANSLATE -> "keyframe_translate";
            case SdfGraph.KEYFRAME_ROTATE -> "keyframe_rotate";
            default -> "op_" + op;
        };
    }
}
