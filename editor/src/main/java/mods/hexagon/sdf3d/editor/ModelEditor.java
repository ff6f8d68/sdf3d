package mods.hexagon.sdf3d.editor;

import mods.hexagon.sdf3d.sdf.SdfGraph;
import mods.hexagon.sdf3d.sdf.SdfModel;
import mods.hexagon.sdf3d.sdf.SdfParser;
import net.minecraft.resources.ResourceLocation;

/**
 * Holds the document being edited: raw {@code .s3d} source, the parsed model, an optional
 * texture, the animation clock, and the last parse error. Reparses on demand and drives the
 * animation time through the graph.
 */
public final class ModelEditor {
    private static final ResourceLocation ID = ResourceLocation.parse("editor:model");

    private String source;
    private SdfModel model;
    private String error;
    private TextureStore texture;
    private float time;

    public ModelEditor(String source) {
        setSource(source);
    }

    public String source() {
        return source;
    }

    public SdfModel model() {
        return model;
    }

    public String error() {
        return error;
    }

    public TextureStore texture() {
        return texture;
    }

    public void setTexture(TextureStore texture) {
        this.texture = texture;
    }

    public float time() {
        return time;
    }

    public void setSource(String source) {
        this.source = source;
        reparse();
    }

    public void reparse() {
        try {
            model = SdfParser.parse(ID, source);
            if (model != null && model.function() instanceof SdfGraph graph) {
                graph.setTime(time);
            }
            error = null;
        } catch (RuntimeException e) {
            model = null;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    public void setTime(float time) {
        this.time = time;
        if (model != null && model.function() instanceof SdfGraph graph) {
            graph.setTime(time);
        }
    }
}
