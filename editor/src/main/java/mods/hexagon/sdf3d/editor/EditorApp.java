package mods.hexagon.sdf3d.editor;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import mods.hexagon.sdf3d.sdf.SdfBounds;
import mods.hexagon.sdf3d.sdf.SdfModel;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Standalone SDF3D editor: a Blender-ish SDF viewport with workspace tabs (Model / Paint /
 * Animate), a node-graph builder that generates {@code .s3d} source from scratch, sculpt brushes
 * (with continuous drag strokes and symmetry), on-model and 2D texture painting, auto-UV, and a
 * dark theme.
 */
public final class EditorApp extends Application {
    private static final int IMG_W = 768;
    private static final int IMG_H = 576;

    private enum Mode { ORBIT, SCULPT, PAINT }
    private enum Workspace { MODEL, PAINT, ANIMATE }

    private final ModelDocument document = new ModelDocument();
    private final ModelEditor editor = new ModelEditor("sphere(0, 0, 0, 0.5)");
    private final Camera camera = new Camera();

    private WritableImage image;
    private PixelWriter writer;
    private ImageView viewport;
    private Label status;
    private TreeView<String> buildTree;
    private TextArea sourceArea;
    private Canvas textureCanvas;
    private Label textureInfo;
    private TextField textureField;
    private TextField tintField;
    private CheckBox emissiveBox;
    private Slider roughnessSlider;
    private Slider metallicSlider;
    private ComboBox<String> easingBox;

    private Workspace workspace = Workspace.MODEL;
    private Mode mode = Mode.ORBIT;
    private SculptOps.Brush brush = SculptOps.Brush.CARVE;
    private double brushRadius = 0.3;
    private double paintSize = 5;
    private double uvScale = 1.0;
    private boolean animate = false;
    private boolean showUvGrid = false;
    private boolean showTextureGrid = true;
    private boolean symmetryX = false;
    private Color paintColor = Color.WHITE;
    private boolean updatingSource = false;
    private boolean treeAuthoritative = true;
    private boolean sculpting = false;
    private boolean painting = false;

    private Gizmo.Mode gizmoMode = Gizmo.Mode.TRANSLATE;
    private boolean gizmoEnabled = true;
    private Gizmo.Handle gizmoHover = Gizmo.Handle.NONE;
    private Gizmo.Handle gizmoActive = Gizmo.Handle.NONE;
    private boolean hoverOnModel = false;
    private ToggleGroup gizmoGroup;
    private final Vector3f pivot = new Vector3f(0f, 0.6f, 0f);
    private double gizmoLastIx;
    private double gizmoLastIy;
    private final Vector3f gizmoStartPivot = new Vector3f();
    private final Vector3f gizmoStartHit = new Vector3f();
    private final Vector3f gizmoStartTranslate = new Vector3f();
    private final Vector3f gizmoStartRotate = new Vector3f();
    private float gizmoStartAngle;

    private double lastX;
    private double lastY;
    private double dragDist;
    private boolean dirty = true;
    private long lastNanos;

    private final Deque<ModelDocument> undoStack = new ArrayDeque<>();
    private final Deque<ModelDocument> redoStack = new ArrayDeque<>();
    private final Map<Integer, EdNode> nodeIndex = new HashMap<>();
    private VBox inspectorPane;
    private Label inspectorName;
    private EdNode inspectorNode;
    private ColorPicker paintColorPicker;
    private Button animateButton;
    private VBox topBar;
    private FlowPane toolbar;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        editor.setTexture(makeGridTexture(64));
        image = new WritableImage(IMG_W, IMG_H);
        writer = image.getPixelWriter();
        viewport = new ImageView(image);
        viewport.setPreserveRatio(true);
        viewport.setSmooth(true);
        viewport.setStyle("-fx-background-color: #17181c;");

        buildTree = new TreeView<>();
        buildTree.setShowRoot(true);
        buildTree.setCellFactory(t -> new TreeCell<String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setContextMenu(empty ? null : contextMenuFor(getTreeItem()));
                if (!empty) installDragAndDrop(this);
            }
        });

        sourceArea = new TextArea();
        sourceArea.setStyle("-fx-font-family: 'DejaVu Sans Mono', monospace; -fx-font-size: 12px;");
        sourceArea.textProperty().addListener((o, p, v) -> {
            if (updatingSource) return;
            treeAuthoritative = false;
            editor.setSource(v);
            status.setText("text edited (node tree detached) — " + editor.error());
            markDirty();
        });

        textureCanvas = new Canvas(256, 256);
        textureCanvas.setOnMousePressed(this::texturePaint);
        textureCanvas.setOnMouseDragged(this::texturePaint);
        textureInfo = new Label();
        status = new Label();
        status.setId("statusLabel");

        topBar = new VBox(buildMenuBar(), buildWorkspaceTabs());
        toolbar = buildToolbar();
        topBar.getChildren().add(toolbar);

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        SplitPane split = new SplitPane(buildLeftPane(), buildViewportPane(), buildRightPane());
        split.setDividerPositions(0.20, 0.76);
        root.setCenter(split);
        root.setBottom(status);

        viewport.setOnMousePressed(this::onPressed);
        viewport.setOnMouseDragged(this::onDragged);
        viewport.setOnMouseReleased(this::onReleased);
        viewport.setOnScroll(this::onScroll);
        viewport.setOnMouseMoved(this::onMouseMoved);
        installViewportMenu();

        buildTree.getSelectionModel().selectedItemProperty().addListener((o, p, item) ->
                showInspector(item instanceof NItem n ? n.node : null));

        regenerateFromTree();
        frameCamera();
        refreshTextureView();

        new AnimationTimer() {
            @Override public void handle(long now) {
                if (lastNanos == 0) lastNanos = now;
                double dt = (now - lastNanos) / 1_000_000_000.0;
                lastNanos = now;
                if (animate) {
                    editor.setTime(editor.time() + (float) dt);
                    dirty = true;
                }
                if (dirty) {
                    dirty = false;
                    renderView();
                }
            }
        }.start();

        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(getClass().getResource("/editor.css").toExternalForm());
        scene.addEventHandler(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        stage.setTitle("SDF3D Editor");
        stage.setScene(scene);
        stage.show();
    }

    // ------------------------------------------------------------------ workspace tabs

    private HBox buildWorkspaceTabs() {
        ToggleGroup group = new ToggleGroup();
        ToggleButton model = new ToggleButton("Model");
        ToggleButton paint = new ToggleButton("Paint");
        ToggleButton animate = new ToggleButton("Animate");
        model.setToggleGroup(group);
        paint.setToggleGroup(group);
        animate.setToggleGroup(group);
        model.setSelected(true);
        model.setOnAction(e -> setWorkspace(Workspace.MODEL));
        paint.setOnAction(e -> setWorkspace(Workspace.PAINT));
        animate.setOnAction(e -> setWorkspace(Workspace.ANIMATE));
        HBox tabs = new HBox(4, model, paint, animate);
        tabs.setPadding(new Insets(4, 8, 0, 8));
        tabs.setStyle("-fx-background-color: #1e1f24;");
        return tabs;
    }

    private void setWorkspace(Workspace w) {
        workspace = w;
        mode = w == Workspace.PAINT ? Mode.PAINT : Mode.ORBIT;
        if (topBar != null) topBar.getChildren().set(2, buildToolbar());
        status.setText(w == Workspace.MODEL ? "Modeling" : w == Workspace.PAINT ? "Painting" : "Animating");
    }

    // ------------------------------------------------------------------ UI construction

    private FlowPane buildToolbar() {
        FlowPane bar = new FlowPane(6, 6);
        bar.setPadding(new Insets(6));

        bar.getChildren().addAll(
                tool("Undo", Icons.line(Icons.UNDO), "Undo (Ctrl+Z)", this::undo),
                tool("Redo", Icons.line(Icons.REDO), "Redo (Ctrl+Y)", this::redo),
                tool("Open", Icons.line(Icons.OPEN), "Open .s3d (Ctrl+O)", this::openModel),
                tool("Save", Icons.line(Icons.SAVE), "Save .s3d (Ctrl+S)", this::saveModel),
                tool("Frame", Icons.line(Icons.FRAME), "Frame the model (F)", () -> { frameCamera(); markDirty(); }),
                tool("Front", null, "Front view", () -> { camera.lookFront(); markDirty(); }),
                tool("Top", null, "Top view", () -> { camera.lookTop(); markDirty(); }),
                tool("Side", null, "Side view", () -> { camera.lookSide(); markDirty(); }),
                tool("Persp", null, "Perspective view", () -> { camera.lookPerspective(); markDirty(); }));

        switch (workspace) {
            case MODEL -> {
                final Button[] newBtnRef = new Button[1];
                Button newBtn = tool("New", Icons.line(Icons.NEW), "New model from a primitive", () -> showNewMenu(newBtnRef[0]));
                newBtnRef[0] = newBtn;
                bar.getChildren().add(0, newBtn);
                bar.getChildren().addAll(vsep(),
                        tool("Duplicate", Icons.line(Icons.DUPLICATE), "Duplicate selected node (Ctrl+D)", this::duplicateSelected),
                        tool("Group", Icons.line(Icons.GROUP), "Make a group from the selection (G)", this::makeGroupFromSelection),
                        tool("Material", null, "Edit material (tint, roughness, metallic, easing)", this::showMaterialDialog),
                        vsep(),
                        gizmoModeToggle("Move", Gizmo.Mode.TRANSLATE),
                        gizmoModeToggle("Rotate", Gizmo.Mode.ROTATE),
                        check("Gizmo", "Show the transform gizmo (arrows / rings / pivot)", gizmoEnabled, v -> { gizmoEnabled = v; markDirty(); }),
                        vsep(),
                        check("Sculpt", "Sculpt on the model with the selected brush", mode == Mode.SCULPT,
                                v -> mode = v ? Mode.SCULPT : Mode.ORBIT),
                        new Label("Brush:"), brushBox(),
                        new Label("Radius"),
                        slider(0.05, 2.0, brushRadius, v -> brushRadius = v.doubleValue()),
                        check("Sym X", "Mirror sculpting across the X axis", symmetryX, v -> symmetryX = v),
                        check("UV grid", "Overlay the UV grid on the model", showUvGrid, v -> { showUvGrid = v; markDirty(); }));
            }
            case PAINT -> {
                paintColorPicker = new ColorPicker(paintColor);
                paintColorPicker.valueProperty().addListener((o, p, v) -> paintColor = v);
                bar.getChildren().addAll(vsep(),
                        new Label("Color:"), paintColorPicker,
                        new Label("Size"),
                        slider(1, 24, paintSize, v -> paintSize = v.doubleValue()),
                        tool("Load", Icons.line(Icons.IMAGE), "Load a texture image", this::loadTexture),
                        tool("Save Tex", Icons.line(Icons.EXPORT), "Save texture as PNG", this::saveTexture),
                        tool("Auto-UV", Icons.line(Icons.UV), "Generate a UV-grid texture", () -> {
                            editor.setTexture(makeGridTexture(64));
                            refreshTextureView();
                            markDirty();
                        }),
                        check("UV grid", "Overlay the UV grid on the model", showUvGrid, v -> { showUvGrid = v; markDirty(); }),
                        check("Tex grid", "Show the pixel grid in the texture panel", showTextureGrid, v -> { showTextureGrid = v; refreshTextureView(); }),
                        new Label("Alt+click picks color from the model"));
            }
            case ANIMATE -> {
                animateButton = new Button("Play");
                animateButton.setGraphic(Icons.fill(Icons.PLAY));
                Tooltip.install(animateButton, new Tooltip("Play/pause animation"));
                animateButton.setOnAction(e -> {
                    animate = !animate;
                    animateButton.setGraphic(animate ? Icons.fill(Icons.PAUSE) : Icons.fill(Icons.PLAY));
                    animateButton.setText(animate ? "Pause" : "Play");
                });
                bar.getChildren().addAll(vsep(), animateButton,
                        new Label("Time"),
                        slider(0, 60, 0, v -> { editor.setTime(v.floatValue()); markDirty(); }),
                        check("UV grid", "Overlay the UV grid on the model", showUvGrid, v -> { showUvGrid = v; markDirty(); }));
            }
        }
        return bar;
    }

    private Slider slider(double min, double max, double value, Consumer<Double> onChange) {
        Slider s = new Slider(min, max, value);
        s.setPrefWidth(120);
        s.valueProperty().addListener((o, p, v) -> onChange.accept(v.doubleValue()));
        return s;
    }

    private ComboBox<SculptOps.Brush> brushBox() {
        ComboBox<SculptOps.Brush> bb = new ComboBox<>();
        bb.getItems().addAll(SculptOps.Brush.values());
        bb.setValue(brush);
        bb.valueProperty().addListener((o, p, v) -> brush = v);
        return bb;
    }

    private Button tool(String label, Node icon, String tip, Runnable action) {
        Button b = new Button(label);
        if (icon != null) b.setGraphic(icon);
        Tooltip.install(b, new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private Separator vsep() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.setMaxHeight(22);
        return s;
    }

    private CheckBox check(String label, String tip, boolean initial, Consumer<Boolean> onToggle) {
        CheckBox cb = new CheckBox(label);
        cb.setSelected(initial);
        Tooltip.install(cb, new Tooltip(tip));
        cb.selectedProperty().addListener((o, p, v) -> onToggle.accept(v));
        return cb;
    }

    private ToggleButton gizmoModeToggle(String label, Gizmo.Mode target) {
        if (gizmoGroup == null) gizmoGroup = new ToggleGroup();
        ToggleButton b = new ToggleButton(label);
        b.setToggleGroup(gizmoGroup);
        b.setSelected(gizmoMode == target);
        Tooltip.install(b, new Tooltip(target == Gizmo.Mode.TRANSLATE
                ? "Show translate arrows — drag one to move the model"
                : "Show rotation rings — drag one to rotate the model"));
        b.setOnAction(e -> { gizmoMode = target; gizmoEnabled = true; markDirty(); });
        return b;
    }

    /** Left panel: texture viewer (top, drawable) + texture selection/creation (bottom). */
    private VBox buildLeftPane() {
        Label heading = new Label("Texture");
        heading.setId("headingLabel");

        Label viewerHeading = new Label("Texture viewer — click to paint");
        VBox viewer = new VBox(4, viewerHeading, textureCanvas, textureInfo);
        viewer.setAlignment(Pos.TOP_CENTER);

        textureField = new TextField();
        textureField.setPromptText("texture name (e.g. editor:grid)");
        textureField.focusedProperty().addListener((o, p, now) -> { if (now) recordUndo(); });
        textureField.textProperty().addListener((o, p, v) -> {
            if (updatingSource) return;
            document.texture = v;
            regenerateFromTree();
        });

        tintField = new TextField("FFFFFFFF");
        tintField.focusedProperty().addListener((o, p, now) -> { if (now) recordUndo(); });
        tintField.textProperty().addListener((o, p, v) -> {
            if (updatingSource) return;
            try {
                document.tint = (int) Long.parseLong(v.trim(), 16);
                regenerateFromTree();
            } catch (NumberFormatException ignored) {
            }
        });

        TextField sizeField = new TextField("64");
        sizeField.setPrefColumnCount(4);
        Button newTex = new Button("New texture");
        newTex.setOnAction(e -> {
            try {
                int size = Integer.parseInt(sizeField.getText().trim());
                editor.setTexture(makeGridTexture(Math.max(4, Math.min(512, size))));
                refreshTextureView();
                markDirty();
            } catch (NumberFormatException ignored) {
                status.setText("bad texture size");
            }
        });

        VBox create = new VBox(4,
                new Label("Texture:"), textureField,
                new HBox(6, new Label("Tint 0x"), tintField),
                new HBox(6, sizeField, newTex),
                new HBox(6,
                        tool("Load", Icons.line(Icons.IMAGE), "Load a texture image", this::loadTexture),
                        tool("Save", Icons.line(Icons.EXPORT), "Save texture as PNG", this::saveTexture)),
                tool("Auto-UV grid", Icons.line(Icons.UV), "Generate a UV-grid texture", () -> {
                    editor.setTexture(makeGridTexture(64));
                    refreshTextureView();
                    markDirty();
                }));

        VBox pane = new VBox(8, heading, viewer, new Separator(), create);
        pane.setPadding(new Insets(8));
        pane.setPrefWidth(240);
        pane.setMinWidth(190);
        VBox.setVgrow(viewer, Priority.ALWAYS);
        return pane;
    }

    /** Right panel: the model graph (full height) with a slide-down inspector for the selection. */
    private VBox buildRightPane() {
        Label heading = new Label("Model");
        heading.setId("headingLabel");

        inspectorName = new Label("Nothing selected");
        inspectorName.setWrapText(true);
        inspectorPane = new VBox(6);
        inspectorPane.setAlignment(Pos.TOP_LEFT);
        inspectorPane.setPadding(new Insets(6, 0, 0, 0));

        VBox pane = new VBox(8, heading, buildTree, new Separator(), inspectorName, inspectorPane);
        pane.setPadding(new Insets(8));
        pane.setPrefWidth(320);
        pane.setMinWidth(240);
        VBox.setVgrow(buildTree, Priority.ALWAYS);
        return pane;
    }

    private BorderPane buildViewportPane() {
        BorderPane pane = new BorderPane();
        pane.setCenter(viewport);
        pane.setStyle("-fx-background-color: #17181c;");
        pane.setPadding(new Insets(4));
        BorderPane.setMargin(viewport, new Insets(4));
        viewport.fitWidthProperty().bind(pane.widthProperty().subtract(16));
        viewport.fitHeightProperty().bind(pane.heightProperty().subtract(16));
        return pane;
    }

    /** Right-click the viewport to add primitives/CSG/transforms/animations to the model. */
    private void installViewportMenu() {
        ContextMenu menu = new ContextMenu();
        Menu add = new Menu("Add");
        for (String category : SdfNodeSpec.categories()) {
            Menu cat = new Menu(category);
            for (SdfNodeSpec spec : SdfNodeSpec.byCategory(category)) {
                MenuItem mi = new MenuItem(spec.name);
                mi.setOnAction(e -> addRootNode(spec.name));
                cat.getItems().add(mi);
            }
            add.getItems().add(cat);
        }
        MenuItem frame = new MenuItem("Frame");
        frame.setOnAction(e -> { frameCamera(); markDirty(); });
        MenuItem undo = new MenuItem("Undo");
        undo.setOnAction(e -> undo());
        MenuItem redo = new MenuItem("Redo");
        redo.setOnAction(e -> redo());
        menu.getItems().addAll(add, new SeparatorMenuItem(), frame, undo, redo);
        viewport.setOnContextMenuRequested(e -> menu.show(viewport, e.getScreenX(), e.getScreenY()));
    }

    private void showNewMenu(Node anchor) {
        ContextMenu menu = new ContextMenu();
        for (SdfNodeSpec spec : SdfNodeSpec.byCategory("Primitive")) {
            MenuItem mi = new MenuItem(spec.name);
            mi.setOnAction(e -> newFromPrimitive(spec.name));
            menu.getItems().add(mi);
        }
        menu.show(anchor, Side.BOTTOM, 0, 0);
    }

    /** Unions a fresh node (with its default children) into the model root. */
    private void addRootNode(String opName) {
        recordUndo();
        EdNode n = new EdNode(opName);
        while (n.children.size() < n.spec.childCount()) n.children.add(new EdNode("sphere"));
        document.root = ModelDocument.unionOf(document.root, n);
        applyBuildEdit();
    }

    // ------------------------------------------------------------------ menu bar, history, inspector

    private MenuBar buildMenuBar() {
        Menu file = new Menu("File");
        file.getItems().addAll(
                item("New", KeyCode.N, () -> newFromPrimitive("sphere")),
                item("Open .s3d…", KeyCode.O, this::openModel),
                item("Save .s3d", KeyCode.S, this::saveModel),
                new SeparatorMenuItem(),
                item("Exit", null, () -> viewport.getScene().getWindow().hide()));

        Menu edit = new Menu("Edit");
        edit.getItems().addAll(
                item("Undo", KeyCode.Z, this::undo),
                item("Redo", KeyCode.Y, this::redo),
                new SeparatorMenuItem(),
                item("Make group…", null, this::makeGroupFromSelection),
                item("Duplicate", null, this::duplicateSelected),
                item("Delete node", null, this::deleteSelected));

        Menu view = new Menu("View");
        view.getItems().addAll(
                item("Frame", null, () -> { frameCamera(); markDirty(); }),
                new SeparatorMenuItem(),
                item("Front", null, () -> { camera.lookFront(); markDirty(); }),
                item("Top", null, () -> { camera.lookTop(); markDirty(); }),
                item("Side", null, () -> { camera.lookSide(); markDirty(); }),
                item("Perspective", null, () -> { camera.lookPerspective(); markDirty(); }));

        Menu help = new Menu("Help");
        help.getItems().add(item("About", null,
                () -> status.setText("SDF3D Editor — model, paint and animate .s3d files")));

        return new MenuBar(file, edit, view, help);
    }

    private MenuItem item(String label, KeyCode code, Runnable action) {
        MenuItem mi = new MenuItem(label);
        if (code != null) mi.setAccelerator(new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN));
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private void onKeyPressed(KeyEvent e) {
        if (e.getTarget() instanceof TextInputControl) return; // don't steal typing
        if (e.isShortcutDown() && e.getCode() == KeyCode.Z && e.isShiftDown()) { redo(); e.consume(); return; }
        if (e.isShortcutDown() && e.getCode() == KeyCode.D) { duplicateSelected(); e.consume(); return; }
        switch (e.getCode()) {
            case DELETE -> deleteSelected();
            case F -> { frameCamera(); markDirty(); }
            case G -> makeGroupFromSelection();
            case DIGIT1 -> mode = Mode.ORBIT;
            case DIGIT2 -> mode = Mode.SCULPT;
            case DIGIT3 -> mode = Mode.PAINT;
            default -> { return; }
        }
        e.consume();
    }

    private void recordUndo() {
        undoStack.push(document.snapshot());
        if (undoStack.size() > 200) undoStack.removeLast();
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(document.snapshot());
        document.restore(undoStack.pop());
        treeAuthoritative = true;
        regenerateFromTree();
        syncPanel();
        status.setText("undid");
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(document.snapshot());
        document.restore(redoStack.pop());
        treeAuthoritative = true;
        regenerateFromTree();
        syncPanel();
        status.setText("redid");
    }

    private void deleteSelected() {
        TreeItem<String> sel = buildTree.getSelectionModel().getSelectedItem();
        if (sel instanceof NItem n) {
            deleteNode(n.node);
            applyBuildEdit();
        }
    }

    private void duplicateSelected() {
        TreeItem<String> sel = buildTree.getSelectionModel().getSelectedItem();
        if (sel instanceof NItem n) duplicateNode(n.node);
    }

    private void duplicateNode(EdNode node) {
        if (node == null) return;
        recordUndo();
        EdNode copy = node.deepCopy();
        EdNode union = new EdNode("union");
        union.children.add(node);
        union.children.add(copy);
        replaceInTree(node, union);
        applyBuildEdit();
    }

    private void showInspector(EdNode node) {
        inspectorNode = node;
        inspectorPane.getChildren().clear();
        if (node == null) {
            inspectorName.setText("Nothing selected");
            return;
        }
        if (node.groupRef != null) {
            inspectorName.setText("group \"" + node.groupRef + "\"");
            Label hint = new Label("references group \"" + node.groupRef + "\" — ungroup via context menu");
            hint.setWrapText(true);
            inspectorPane.getChildren().add(hint);
            return;
        }
        inspectorName.setText(node.name());
        if (node.spec.paramCount() == 0) {
            inspectorPane.getChildren().add(new Label(node.spec.childCount() == 0
                    ? "no editable parameters" : "edit children via the tree"));
            return;
        }
        for (int i = 0; i < node.spec.paramCount(); i++) {
            final int idx = i;
            Label lbl = new Label(node.spec.params[i]);
            lbl.setMinWidth(56);
            TextField tf = new TextField(SdfWriter.fmt(node.params[i]));
            tf.setPrefColumnCount(6);
            HBox.setHgrow(tf, Priority.ALWAYS);
            tf.setOnAction(ev -> commitInspectorField(node, idx, tf));
            tf.focusedProperty().addListener((o, p, now) -> { if (!now) commitInspectorField(node, idx, tf); });
            inspectorPane.getChildren().add(new HBox(4, lbl, tf));
        }
    }

    private void commitInspectorField(EdNode node, int idx, TextField tf) {
        if (inspectorNode != node) return;
        try {
            float v = Float.parseFloat(tf.getText().trim());
            if (node.params[idx] == v) return;
            recordUndo();
            node.params[idx] = v;
            treeAuthoritative = true;
            regenerateSourceOnly();
            TreeItem<String> sel = buildTree.getSelectionModel().getSelectedItem();
            if (sel instanceof NItem n) n.setValue(node.label());
        } catch (NumberFormatException ignored) {
            tf.setText(SdfWriter.fmt(node.params[idx]));
        }
    }

    // ------------------------------------------------------------------ node graph editing

    private static final class NItem extends TreeItem<String> {
        final EdNode node;
        NItem(EdNode node) {
            super(node.label());
            this.node = node;
        }
    }

    private static final class GroupItem extends TreeItem<String> {
        final String name;
        GroupItem(String name) {
            super("group \"" + name + "\"");
            this.name = name;
        }
    }

    /** Drag a node onto another node (or a group) to re-parent it. */
    private void installDragAndDrop(TreeCell<String> cell) {
        cell.setOnDragDetected(ev -> {
            if (cell.getTreeItem() instanceof NItem n) {
                Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(String.valueOf(System.identityHashCode(n.node)));
                db.setContent(cc);
                ev.consume();
            }
        });
        cell.setOnDragOver(ev -> {
            if (ev.getGestureSource() != cell && ev.getDragboard().hasString()) {
                ev.acceptTransferModes(TransferMode.MOVE);
            }
            ev.consume();
        });
        cell.setOnDragDropped(ev -> {
            String id = ev.getDragboard().getString();
            if (id != null) {
                try {
                    EdNode dragged = nodeIndex.get(Integer.parseInt(id));
                    if (dragged != null && cell.getTreeItem() instanceof NItem target) {
                        moveNodeInto(dragged, target.node);
                        ev.setDropCompleted(true);
                    } else if (dragged != null && cell.getTreeItem() instanceof GroupItem g) {
                        moveNodeIntoGroup(dragged, g.name);
                        ev.setDropCompleted(true);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            ev.consume();
        });
    }

    private void moveNodeInto(EdNode node, EdNode target) {
        if (node == null || target == null || node == target || node == document.root) return;
        if (target.groupRef != null || containsNode(node, target)) return;
        recordUndo();
        detach(node);
        if (target.spec.childCount() == 0 || target.children.size() >= target.spec.childCount()) {
            replaceInTree(target, ModelDocument.unionOf(target, node));
        } else {
            target.children.add(node);
        }
        applyBuildEdit();
    }

    private void moveNodeIntoGroup(EdNode node, String name) {
        if (node == null || node == document.root) return;
        recordUndo();
        detach(node);
        document.addToGroup(name, node);
        applyBuildEdit();
    }

    private boolean detach(EdNode node) {
        if (document.root == node) return false;
        for (Map.Entry<String, EdNode> e : new ArrayList<>(document.groups.entrySet())) {
            if (containsNode(e.getValue(), node)) {
                document.groups.put(e.getKey(), removeFromTree(e.getValue(), node));
                return true;
            }
        }
        if (containsNode(document.root, node)) {
            document.root = removeFromTree(document.root, node);
            return true;
        }
        return false;
    }

    private void refreshBuildTree() {
        nodeIndex.clear();
        TreeItem<String> root = new TreeItem<>("scene");

        TreeItem<String> modelSection = new TreeItem<>("Model");
        NItem modelRoot = buildItem(document.root);
        modelRoot.setExpanded(true);
        modelSection.getChildren().add(modelRoot);
        modelSection.setExpanded(true);
        root.getChildren().add(modelSection);

        TreeItem<String> groupsSection = new TreeItem<>("Groups");
        for (String name : document.groups.keySet()) {
            GroupItem gi = new GroupItem(name);
            NItem body = buildItem(document.groups.get(name));
            body.setExpanded(true);
            gi.getChildren().add(body);
            groupsSection.getChildren().add(gi);
        }
        groupsSection.setExpanded(true);
        root.getChildren().add(groupsSection);

        root.setExpanded(true);
        buildTree.setRoot(root);
    }

    private NItem buildItem(EdNode node) {
        nodeIndex.put(System.identityHashCode(node), node);
        NItem item = new NItem(node);
        for (EdNode child : node.children) item.getChildren().add(buildItem(child));
        return item;
    }

    private ContextMenu contextMenuFor(TreeItem<String> item) {
        if (item instanceof NItem n) return nodeMenu(n.node);
        if (item instanceof GroupItem g) return groupMenu(g.name);
        return null;
    }

    private ContextMenu nodeMenu(EdNode node) {
        ContextMenu menu = new ContextMenu();
        boolean inGroup = document.groupOf(node) != null;

        MenuItem makeGroup = new MenuItem("Make group…");
        makeGroup.setDisable(inGroup);
        makeGroup.setOnAction(e -> makeGroupFromNode(node));

        Menu addToGroup = new Menu("Add to group");
        for (String name : document.groups.keySet()) {
            if (document.groupContains(name, node)) continue;
            MenuItem mi = new MenuItem(name);
            mi.setOnAction(e -> addNodeToGroup(node, name));
            addToGroup.getItems().add(mi);
        }
        addToGroup.setDisable(inGroup || addToGroup.getItems().isEmpty());

        MenuItem ungroup = new MenuItem("Ungroup");
        ungroup.setDisable(node.groupRef == null);
        ungroup.setOnAction(e -> ungroupNode(node));

        MenuItem moveToModel = new MenuItem("Move to model");
        moveToModel.setDisable(!inGroup);
        moveToModel.setOnAction(e -> moveNodeToModel(node));

        Menu addChild = new Menu("Add child");
        addChild.setDisable(node.groupRef != null || node.spec.childCount() <= node.children.size());
        for (String category : SdfNodeSpec.categories()) {
            Menu cat = new Menu(category);
            for (SdfNodeSpec spec : SdfNodeSpec.byCategory(category)) {
                MenuItem mi = new MenuItem(spec.name);
                mi.setOnAction(e -> {
                    addChild(node, spec.name);
                    applyBuildEdit();
                });
                cat.getItems().add(mi);
            }
            addChild.getItems().add(cat);
        }
        if (!document.groups.isEmpty()) {
            Menu grp = new Menu("Group");
            for (String name : document.groups.keySet()) {
                MenuItem mi = new MenuItem(name);
                mi.setOnAction(e -> {
                    if (node.children.size() < node.spec.childCount()) {
                        node.children.add(EdNode.groupRef(name));
                        applyBuildEdit();
                    }
                });
                grp.getItems().add(mi);
            }
            addChild.getItems().add(grp);
        }

        Menu wrap = new Menu("Wrap");
        for (String category : SdfNodeSpec.categories()) {
            Menu cat = new Menu(category);
            for (SdfNodeSpec spec : SdfNodeSpec.byCategory(category)) {
                if (spec.childCount() == 0) continue;
                MenuItem mi = new MenuItem(spec.name);
                mi.setOnAction(e -> {
                    wrapWith(node, spec.name);
                    applyBuildEdit();
                });
                cat.getItems().add(mi);
            }
            if (!cat.getItems().isEmpty()) wrap.getItems().add(cat);
        }

        Menu replace = new Menu("Replace with");
        for (String category : SdfNodeSpec.categories()) {
            Menu cat = new Menu(category);
            for (SdfNodeSpec spec : SdfNodeSpec.byCategory(category)) {
                if (spec.childCount() != 0) continue;
                MenuItem mi = new MenuItem(spec.name);
                mi.setOnAction(e -> {
                    recordUndo();
                    node.replace(spec);
                    applyBuildEdit();
                });
                cat.getItems().add(mi);
            }
            if (!cat.getItems().isEmpty()) replace.getItems().add(cat);
        }

        MenuItem editParams = new MenuItem("Edit parameters…");
        editParams.setDisable(node.groupRef != null || node.spec.paramCount() == 0);
        editParams.setOnAction(e -> editParams(node));

        MenuItem duplicate = new MenuItem("Duplicate");
        duplicate.setOnAction(e -> duplicateNode(node));

        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(e -> {
            deleteNode(node);
            applyBuildEdit();
        });

        menu.getItems().addAll(makeGroup, addToGroup, ungroup, moveToModel, new SeparatorMenuItem(),
                addChild, wrap, replace, editParams, duplicate, new SeparatorMenuItem(), delete);
        return menu;
    }

    private ContextMenu groupMenu(String name) {
        ContextMenu menu = new ContextMenu();
        MenuItem rename = new MenuItem("Rename group…");
        rename.setOnAction(e -> renameGroup(name));
        MenuItem del = new MenuItem("Delete group");
        del.setOnAction(e -> {
            recordUndo();
            document.removeGroup(name);
            applyBuildEdit();
        });
        menu.getItems().addAll(rename, del);
        return menu;
    }

    private void addChild(EdNode target, String opName) {
        if (target == null || target.children.size() >= target.spec.childCount()) return;
        recordUndo();
        EdNode child = new EdNode(opName);
        while (child.children.size() < child.spec.childCount()) child.children.add(new EdNode("sphere"));
        target.children.add(child);
    }

    private void wrapWith(EdNode target, String opName) {
        if (target == null) return;
        recordUndo();
        EdNode wrapper = new EdNode(opName);
        wrapper.children.add(target);
        while (wrapper.children.size() < wrapper.spec.childCount()) wrapper.children.add(new EdNode("sphere"));
        replaceInTree(target, wrapper);
    }

    private void deleteNode(EdNode target) {
        if (target == null) return;
        recordUndo();
        if (document.root == target) {
            document.root = new EdNode("sphere");
            return;
        }
        for (Map.Entry<String, EdNode> e : new ArrayList<>(document.groups.entrySet())) {
            if (containsNode(e.getValue(), target)) {
                document.groups.put(e.getKey(), removeFromTree(e.getValue(), target));
                return;
            }
        }
        if (containsNode(document.root, target)) {
            document.root = removeFromTree(document.root, target);
        }
    }

    private void replaceInTree(EdNode target, EdNode replacement) {
        if (document.root == target) {
            document.root = replacement;
            return;
        }
        for (Map.Entry<String, EdNode> e : new ArrayList<>(document.groups.entrySet())) {
            if (containsNode(e.getValue(), target)) {
                document.groups.put(e.getKey(), replaceWithin(e.getValue(), target, replacement));
                return;
            }
        }
        if (containsNode(document.root, target)) {
            document.root = replaceWithin(document.root, target, replacement);
        }
    }

    private boolean containsNode(EdNode current, EdNode target) {
        if (current == target) return true;
        for (EdNode child : current.children) if (containsNode(child, target)) return true;
        return false;
    }

    private EdNode removeFromTree(EdNode current, EdNode target) {
        if (current == target) return new EdNode("sphere");
        current.children.remove(target);
        for (int i = 0; i < current.children.size(); i++) {
            if (containsNode(current.children.get(i), target)) {
                current.children.set(i, removeFromTree(current.children.get(i), target));
            }
        }
        if (current.spec.childCount() == 2 && current.children.size() == 1) {
            return current.children.get(0);
        }
        if (current.children.isEmpty() && current.spec.childCount() > 0) {
            return new EdNode("sphere");
        }
        return current;
    }

    private EdNode replaceWithin(EdNode current, EdNode target, EdNode replacement) {
        if (current == target) return replacement;
        for (int i = 0; i < current.children.size(); i++) {
            if (current.children.get(i) == target) {
                current.children.set(i, replacement);
                return current;
            }
        }
        for (int i = 0; i < current.children.size(); i++) {
            if (containsNode(current.children.get(i), target)) {
                current.children.set(i, replaceWithin(current.children.get(i), target, replacement));
            }
        }
        return current;
    }

    // ------------------------------------------------------------------ group operations

    private void makeGroupFromSelection() {
        TreeItem<String> sel = buildTree.getSelectionModel().getSelectedItem();
        if (sel instanceof NItem n) makeGroupFromNode(n.node);
        else status.setText("select a node first");
    }

    private void makeGroupFromNode(EdNode node) {
        TextInputDialog dlg = new TextInputDialog("group1");
        dlg.setTitle("Make group");
        dlg.setHeaderText("Name for the new group");
        dlg.setContentText("Name:");
        Optional<String> result = dlg.showAndWait();
        if (result.isEmpty() || result.get().isBlank()) return;
        String name = result.get().trim();
        if (document.groups.containsKey(name)) {
            status.setText("group '" + name + "' already exists");
            return;
        }
        recordUndo();
        replaceInTree(node, EdNode.groupRef(name));
        document.makeGroup(name, node);
        applyBuildEdit();
    }

    private void addNodeToGroup(EdNode node, String name) {
        recordUndo();
        replaceInTree(node, EdNode.groupRef(name));
        document.addToGroup(name, node);
        applyBuildEdit();
    }

    private void ungroupNode(EdNode node) {
        EdNode body = document.groups.get(node.groupRef);
        if (body == null) return;
        recordUndo();
        replaceInTree(node, body.deepCopy());
        applyBuildEdit();
    }

    private void moveNodeToModel(EdNode node) {
        recordUndo();
        document.removeFromGroup(node);
        document.root = ModelDocument.unionOf(document.root, node);
        applyBuildEdit();
    }

    private void renameGroup(String name) {
        TextInputDialog dlg = new TextInputDialog(name);
        dlg.setTitle("Rename group");
        dlg.setHeaderText("New name for group '" + name + "'");
        dlg.setContentText("Name:");
        Optional<String> result = dlg.showAndWait();
        if (result.isEmpty() || result.get().isBlank()) return;
        String newName = result.get().trim();
        if (newName.equals(name)) return;
        if (document.groups.containsKey(newName)) {
            status.setText("group '" + newName + "' already exists");
            return;
        }
        recordUndo();
        document.renameGroup(name, newName);
        applyBuildEdit();
    }

    private void applyBuildEdit() {
        treeAuthoritative = true;
        regenerateFromTree();
    }

    private void regenerateFromTree() {
        treeAuthoritative = true;
        regenerateSourceOnly();
        refreshBuildTree();
    }

    private void regenerateSourceOnly() {
        String source = document.toSource();
        updatingSource = true;
        sourceArea.setText(source);
        updatingSource = false;
        editor.setSource(source);
        status.setText("nodes: " + nodeCount() + "    " + (editor.error() == null ? "ok" : editor.error()));
        markDirty();
    }

    private int nodeCount() {
        return editor.model() == null ? 0 : ((mods.hexagon.sdf3d.sdf.SdfGraph) editor.model().function()).nodeCount();
    }

    private void syncPanel() {
        updatingSource = true;
        textureField.setText(document.texture == null ? "" : document.texture);
        tintField.setText(Integer.toHexString(document.tint).toUpperCase());
        updatingSource = false;
    }

    private void newFromPrimitive(String name) {
        recordUndo();
        document.root = new EdNode(name);
        document.texture = null;
        document.tint = 0xFFFFFFFF;
        document.emissive = false;
        document.roughness = 0.6f;
        document.metallic = 0.1f;
        document.easing = "linear";
        treeAuthoritative = true;
        regenerateFromTree();
        syncPanel();
        frameCamera();
    }

    private void showMaterialDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Material");
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));

        Slider rough = new Slider(0, 1, document.roughness);
        Slider metal = new Slider(0, 1, document.metallic);
        CheckBox emissive = new CheckBox("Emissive");
        emissive.setSelected(document.emissive);
        ComboBox<String> easing = new ComboBox<>();
        easing.getItems().addAll(ModelDocument.EASINGS);
        easing.setValue(document.easing);
        grid.add(new Label("Roughness"), 0, 0);
        grid.add(rough, 1, 0);
        grid.add(new Label("Metallic"), 0, 1);
        grid.add(metal, 1, 1);
        grid.add(emissive, 0, 2, 2, 1);
        grid.add(new Label("Easing"), 0, 3);
        grid.add(easing, 1, 3);

        dialog.getDialogPane().setContent(grid);
        ButtonType ok = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(bt -> {
            if (bt != ok) return null;
            recordUndo();
            document.roughness = (float) rough.getValue();
            document.metallic = (float) metal.getValue();
            document.emissive = emissive.isSelected();
            document.easing = easing.getValue();
            regenerateFromTree();
            return null;
        });
        dialog.showAndWait();
    }

    private void editParams(EdNode node) {
        if (node == null) return;
        Dialog<boolean[]> dialog = new Dialog<>();
        dialog.setTitle("Edit " + node.name());
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(12));
        List<TextField> fields = new ArrayList<>();
        for (int i = 0; i < node.spec.paramCount(); i++) {
            grid.add(new Label(node.spec.params[i] + ":"), 0, i);
            TextField tf = new TextField(SdfWriter.fmt(node.params[i]));
            fields.add(tf);
            grid.add(tf, 1, i);
        }
        dialog.getDialogPane().setContent(grid);
        ButtonType ok = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(bt -> {
            if (bt != ok) return null;
            float[] vals = new float[fields.size()];
            for (int i = 0; i < fields.size(); i++) {
                try {
                    vals[i] = Float.parseFloat(fields.get(i).getText().trim());
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            recordUndo();
            for (int i = 0; i < vals.length; i++) node.params[i] = vals[i];
            return new boolean[]{true};
        });
        dialog.showAndWait().ifPresent(r -> applyBuildEdit());
    }

    // ------------------------------------------------------------------ rendering

    private void renderView() {
        SdfModel model = editor.model();
        if (model == null) {
            for (int y = 0; y < IMG_H; y++) {
                for (int x = 0; x < IMG_W; x++) writer.setArgb(x, y, Renderer.BACKGROUND);
            }
            return;
        }
        int[] pixels = Renderer.render(model, editor.texture(), camera, IMG_W, IMG_H, (float) uvScale, showUvGrid);
        if (gizmoEnabled && workspace == Workspace.MODEL && mode == Mode.ORBIT) {
            if (gizmoActive == Gizmo.Handle.NONE) updatePivot();
            Gizmo.render(pixels, IMG_W, IMG_H, camera, (float) IMG_W / IMG_H, pivot, gizmoMode, gizmoHover);
        }
        for (int y = 0; y < IMG_H; y++) {
            for (int x = 0; x < IMG_W; x++) writer.setArgb(x, y, pixels[y * IMG_W + x]);
        }
    }

    private void markDirty() {
        dirty = true;
    }

    private void frameCamera() {
        SdfModel model = editor.model();
        if (model == null) return;
        SdfBounds b = model.bounds();
        Vector3f min = b.min();
        Vector3f max = b.max();
        if (!Float.isFinite(min.x()) || !Float.isFinite(max.x())) return;
        Vector3f half = new Vector3f(max).sub(min).mul(0.5f);
        camera.target().set(new Vector3f(min).add(half));
        pivot.set(new Vector3f(min).add(half));
        camera.frameRadius(Math.max(0.5f, half.length()));
    }

    private void refreshTextureView() {
        GraphicsContext g = textureCanvas.getGraphicsContext2D();
        double w = textureCanvas.getWidth();
        double h = textureCanvas.getHeight();
        g.setFill(Color.rgb(40, 40, 44));
        g.fillRect(0, 0, w, h);
        TextureStore t = editor.texture();
        if (t == null) {
            textureInfo.setText("no texture");
            return;
        }
        textureInfo.setText(t.width() + " x " + t.height() + " px");
        double scale = Math.min(w / t.width(), h / t.height());
        double ox = (w - t.width() * scale) / 2;
        double oy = (h - t.height() * scale) / 2;
        double cell = Math.max(1, scale);
        for (int y = 0; y < t.height(); y++) {
            for (int x = 0; x < t.width(); x++) {
                g.setFill(toColor(t.getPixel(x, y)));
                g.fillRect(ox + x * scale, oy + y * scale, cell, cell);
            }
        }
        if (showTextureGrid && cell >= 3) {
            g.setStroke(Color.rgb(0, 0, 0, 0.35));
            g.setLineWidth(1);
            for (int x = 0; x <= t.width(); x++) {
                g.strokeLine(ox + x * scale, oy, ox + x * scale, oy + t.height() * scale);
            }
            for (int y = 0; y <= t.height(); y++) {
                g.strokeLine(ox, oy + y * scale, ox + t.width() * scale, oy + y * scale);
            }
        }
    }

    private static Color toColor(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return Color.rgb(r, g, b, a / 255.0);
    }

    private static TextureStore makeGridTexture(int size) {
        TextureStore t = new TextureStore(size, size);
        int[] p = t.pixels();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean border = x == 0 || y == 0 || x == size - 1 || y == size - 1;
                boolean major = (x % 8 == 0) || (y % 8 == 0);
                p[y * size + x] = border ? 0xFF404040 : major ? 0xFF9A9A9A : ((x / 8 + y / 8) & 1) == 0 ? 0xFFE8E8E8 : 0xFFC8C8C8;
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ interaction

    private void onPressed(MouseEvent e) {
        lastX = e.getX();
        lastY = e.getY();
        dragDist = 0;
        if (mode == Mode.SCULPT) {
            sculpting = true;
            recordUndo();
            return;
        }
        if (mode == Mode.PAINT && !e.isAltDown()) {
            painting = true;
            return;
        }
        // Orbit mode: try the transform gizmo before orbiting the camera.
        if (gizmoEnabled && workspace == Workspace.MODEL) {
            double[] xy = imageXY(e);
            if (xy != null) {
                Gizmo.Handle h = Gizmo.hitTest(camera, (float) IMG_W / IMG_H, IMG_W, IMG_H,
                        pivot, gizmoMode, (float) xy[0], (float) xy[1]);
                if (h != Gizmo.Handle.NONE) {
                    beginGizmoDrag(h, (float) xy[0], (float) xy[1]);
                    return;
                }
            }
        }
    }

    private void onDragged(MouseEvent e) {
        double dx = e.getX() - lastX;
        double dy = e.getY() - lastY;
        lastX = e.getX();
        lastY = e.getY();
        dragDist += Math.abs(dx) + Math.abs(dy);

        if (gizmoActive != Gizmo.Handle.NONE) {
            gizmoDrag(e);
            return;
        }
        if (sculpting) {
            SdfRaycaster.Hit hit = marchAt(e);
            if (hit != null) applyBrush(hit.position().x(), hit.position().y(), hit.position().z());
            markDirty();
            return;
        }
        if (painting) {
            SdfRaycaster.Hit hit = marchAt(e);
            if (hit != null) applyPaint(hit);
            return;
        }
        if (e.isShiftDown()) camera.pan((float) dx, (float) dy);
        else camera.orbit((float) (dx * 0.01), (float) (dy * 0.01));
        markDirty();
    }

    private void onReleased(MouseEvent e) {
        if (gizmoActive != Gizmo.Handle.NONE) {
            gizmoActive = Gizmo.Handle.NONE;
            applyBuildEdit();
            markDirty();
            return;
        }
        if (sculpting) {
            sculpting = false;
            applyBuildEdit();
            return;
        }
        if (painting) {
            painting = false;
            refreshTextureView();
            markDirty();
            return;
        }
        if (dragDist >= 4) return;
        SdfRaycaster.Hit hit = marchAt(e);
        if (hit == null) return;
        switch (mode) {
            case SCULPT -> applySculpt(hit);
            case PAINT -> {
                if (e.isAltDown()) pickColor(hit); else applyPaint(hit);
            }
            case ORBIT -> selectAt(hit);
        }
    }

    /** Click on the model (not a gizmo, not a drag) selects the node under the cursor. */
    private void selectAt(SdfRaycaster.Hit hit) {
        EdNode picked = EdPicker.pick(document.root, document.groups, hit.position(), editor.time());
        if (picked == null) return;
        selectNodeInTree(picked);
    }

    private void selectNodeInTree(EdNode node) {
        TreeItem<String> item = findTreeItem(buildTree.getRoot(), node);
        if (item == null) return;
        for (TreeItem<String> p = item.getParent(); p != null; p = p.getParent()) p.setExpanded(true);
        buildTree.getSelectionModel().select(item);
        buildTree.scrollTo(buildTree.getRow(item));
        status.setText("selected " + node.label());
    }

    private TreeItem<String> findTreeItem(TreeItem<String> item, EdNode node) {
        if (item instanceof NItem n && n.node == node) return item;
        for (TreeItem<String> child : item.getChildren()) {
            TreeItem<String> found = findTreeItem(child, node);
            if (found != null) return found;
        }
        return null;
    }

    private void onMouseMoved(MouseEvent e) {
        Gizmo.Handle h = Gizmo.Handle.NONE;
        boolean overModel = false;
        if (gizmoEnabled && workspace == Workspace.MODEL && mode == Mode.ORBIT && gizmoActive == Gizmo.Handle.NONE) {
            double[] xy = imageXY(e);
            if (xy != null) {
                h = Gizmo.hitTest(camera, (float) IMG_W / IMG_H, IMG_W, IMG_H,
                        pivot, gizmoMode, (float) xy[0], (float) xy[1]);
                if (h == Gizmo.Handle.NONE) overModel = marchAt(e) != null;
            }
        }
        if (h != gizmoHover) { gizmoHover = h; markDirty(); }
        hoverOnModel = overModel;
        viewport.setCursor(cursorFor(h, overModel));
    }

    private Cursor cursorFor(Gizmo.Handle h, boolean overModel) {
        if (h == Gizmo.Handle.AXIS_X || h == Gizmo.Handle.AXIS_Y || h == Gizmo.Handle.AXIS_Z) return Cursor.MOVE;
        if (h == Gizmo.Handle.RING_X || h == Gizmo.Handle.RING_Y || h == Gizmo.Handle.RING_Z) return Cursor.CROSSHAIR;
        if (overModel) return Cursor.HAND;
        return Cursor.DEFAULT;
    }

    // ------------------------------------------------------------------ transform gizmo

    private void beginGizmoDrag(Gizmo.Handle h, float ix, float iy) {
        gizmoActive = h;
        gizmoLastIx = ix;
        gizmoLastIy = iy;
        recordUndo();
        gizmoStartPivot.set(pivot);
        Vector3f axis = axisOf(h);
        if (axis == null) return;
        float aspect = (float) IMG_W / IMG_H;
        if (isAxisHandle(h)) {
            float u = (float) (ix / IMG_W * 2 - 1);
            float v = (float) (1 - iy / IMG_H * 2);
            Vector3f hit = Gizmo.planeHit(camera, aspect, gizmoStartPivot, axis, u, v);
            gizmoStartHit.set(hit == null ? gizmoStartPivot : hit);
            float[] t = currentTransform("translate");
            gizmoStartTranslate.set(t == null ? 0 : t[0], t == null ? 0 : t[1], t == null ? 0 : t[2]);
        } else {
            gizmoStartAngle = Gizmo.screenAngle(camera, aspect, IMG_W, IMG_H, gizmoStartPivot, ix, iy);
            float[] r = currentTransform("rotate");
            gizmoStartRotate.set(r == null ? 0 : r[0], r == null ? 0 : r[1], r == null ? 0 : r[2]);
        }
        markDirty();
    }

    private void gizmoDrag(MouseEvent e) {
        double[] xy = imageXY(e);
        if (xy == null) return;
        float aspect = (float) IMG_W / IMG_H;
        Vector3f axis = axisOf(gizmoActive);
        if (axis == null) return;
        int idx = axisIndex(axis);
        if (isAxisHandle(gizmoActive)) {
            float u = (float) (xy[0] / IMG_W * 2 - 1);
            float v = (float) (1 - xy[1] / IMG_H * 2);
            Vector3f hit = Gizmo.planeHit(camera, aspect, gizmoStartPivot, axis, u, v);
            if (hit == null) return;
            float delta = new Vector3f(hit).sub(gizmoStartHit).dot(axis);
            float[] s = {gizmoStartTranslate.x(), gizmoStartTranslate.y(), gizmoStartTranslate.z()};
            s[idx] += delta;
            setTransform("translate", s[0], s[1], s[2]);
            // keep the pivot glued to the object centre so the gizmo follows the model
            pivot.set(gizmoStartPivot).add(s[0] - gizmoStartTranslate.x(), s[1] - gizmoStartTranslate.y(), s[2] - gizmoStartTranslate.z());
        } else {
            float ang = Gizmo.screenAngle(camera, aspect, IMG_W, IMG_H, gizmoStartPivot, (float) xy[0], (float) xy[1]);
            float d = ang - gizmoStartAngle;
            while (d > Math.PI) d -= 2f * (float) Math.PI;
            while (d < -Math.PI) d += 2f * (float) Math.PI;
            float deg = (float) Math.toDegrees(d) * Gizmo.rotationSign(camera, axis);
            float[] s = {gizmoStartRotate.x(), gizmoStartRotate.y(), gizmoStartRotate.z()};
            s[idx] += deg;
            setTransform("rotate", s[0], s[1], s[2]);
        }
        regenerateSourceOnly();
    }

    private float[] currentTransform(String op) {
        EdNode root = document.root;
        if ("rotate".equals(op)) {
            EdNode rot = findRotateWrapper(root);
            return rot == null ? null : rot.params.clone();
        }
        // translate: the outermost translate that is NOT a rotation sandwich
        if ("translate".equals(root.name()) && root.children.size() == 1 && !isSandwich(root)) {
            return root.params.clone();
        }
        return null;
    }

    private void setTransform(String op, float p0, float p1, float p2) {
        EdNode root = document.root;
        if ("rotate".equals(op)) {
            // Rotate around the object centre: translate(p) -> rotate(r) -> translate(-p) -> inner.
            EdNode sandwich = findSandwich(root);
            if (sandwich != null) {
                EdNode rot = sandwich.children.get(0);
                rot.params[0] = p0; rot.params[1] = p1; rot.params[2] = p2;
                // re-centre on the current pivot (handles a translate that happened before rotating)
                sandwich.params[0] = pivot.x(); sandwich.params[1] = pivot.y(); sandwich.params[2] = pivot.z();
                EdNode tNeg = rot.children.get(0);
                tNeg.params[0] = -pivot.x(); tNeg.params[1] = -pivot.y(); tNeg.params[2] = -pivot.z();
            } else {
                EdNode tPos = new EdNode("translate");
                tPos.setParams(pivot.x(), pivot.y(), pivot.z());
                EdNode rot = new EdNode("rotate");
                rot.setParams(p0, p1, p2);
                EdNode tNeg = new EdNode("translate");
                tNeg.setParams(-pivot.x(), -pivot.y(), -pivot.z());
                tNeg.children.add(root);
                rot.children.add(tNeg);
                tPos.children.add(rot);
                document.root = tPos;
            }
            return;
        }
        // translate
        if ("translate".equals(root.name()) && root.children.size() == 1 && !isSandwich(root)) {
            root.params[0] = p0; root.params[1] = p1; root.params[2] = p2;
        } else {
            EdNode t = new EdNode("translate");
            t.setParams(p0, p1, p2);
            t.children.add(root);
            document.root = t;
        }
    }

    /** The rotate node inside the pivot-rotation sandwich, or null. */
    private EdNode findRotateWrapper(EdNode root) {
        EdNode sandwich = findSandwich(root);
        return sandwich == null ? null : sandwich.children.get(0);
    }

    /** Locates the rotation sandwich ({@code translate → rotate → translate}) at the root level. */
    private EdNode findSandwich(EdNode root) {
        if (isSandwich(root)) return root;
        if ("translate".equals(root.name()) && root.children.size() == 1 && isSandwich(root.children.get(0))) {
            return root.children.get(0);
        }
        return null;
    }

    private boolean isSandwich(EdNode n) {
        if (!"translate".equals(n.name()) || n.children.size() != 1) return false;
        EdNode r = n.children.get(0);
        if (!"rotate".equals(r.name()) || r.children.size() != 1) return false;
        EdNode t = r.children.get(0);
        return "translate".equals(t.name()) && t.children.size() == 1;
    }

    private static boolean isAxisHandle(Gizmo.Handle h) {
        return h == Gizmo.Handle.AXIS_X || h == Gizmo.Handle.AXIS_Y || h == Gizmo.Handle.AXIS_Z;
    }

    private static Vector3f axisOf(Gizmo.Handle h) {
        return switch (h) {
            case AXIS_X, RING_X -> new Vector3f(1, 0, 0);
            case AXIS_Y, RING_Y -> new Vector3f(0, 1, 0);
            case AXIS_Z, RING_Z -> new Vector3f(0, 0, 1);
            default -> null;
        };
    }

    private static int axisIndex(Vector3f a) {
        if (a.x() != 0) return 0;
        if (a.y() != 0) return 1;
        return 2;
    }

    private double[] imageXY(MouseEvent e) {
        double W = viewport.getFitWidth();
        double H = viewport.getFitHeight();
        if (W <= 0 || H <= 0) return null;
        double scale = Math.min(W / IMG_W, H / IMG_H);
        double dw = IMG_W * scale, dh = IMG_H * scale;
        double ox = (W - dw) / 2, oy = (H - dh) / 2;
        double ix = (e.getX() - ox) / scale;
        double iy = (e.getY() - oy) / scale;
        if (ix < 0 || iy < 0 || ix >= IMG_W || iy >= IMG_H) return null;
        return new double[]{ix, iy};
    }

    private void updatePivot() {
        SdfModel m = editor.model();
        if (m == null) return;
        SdfBounds b = m.bounds();
        if (!Float.isFinite(b.min().x()) || !Float.isFinite(b.max().x())) return;
        pivot.set(new Vector3f(b.min()).add(b.max()).mul(0.5f));
    }

    private SdfRaycaster.Hit marchAt(MouseEvent e) {
        Vector3f dir = rayFor(e);
        if (dir == null) return null;
        SdfModel model = editor.model();
        if (model == null) return null;
        return SdfRaycaster.march(model, camera.eye(new Vector3f()), dir,
                Renderer.marchRadius(model), 512, Renderer.epsilon(model));
    }

    private void applySculpt(SdfRaycaster.Hit hit) {
        recordUndo();
        applyBrush(hit.position().x(), hit.position().y(), hit.position().z());
        if (symmetryX && Math.abs(hit.position().x()) > 1e-3f) {
            applyBrush(-hit.position().x(), hit.position().y(), hit.position().z());
        }
        applyBuildEdit();
    }

    private void applyBrush(float x, float y, float z) {
        EdNode brushNode = new EdNode("sphere");
        brushNode.params[0] = x;
        brushNode.params[1] = y;
        brushNode.params[2] = z;
        float r = (float) brushRadius;
        if (brush == SculptOps.Brush.INFLATE || brush == SculptOps.Brush.DEFLATE) r *= 2.2f;
        brushNode.params[3] = r;
        boolean subtract = brush == SculptOps.Brush.CARVE || brush == SculptOps.Brush.DEFLATE;
        boolean smooth = brush == SculptOps.Brush.BLEND;
        EdNode wrapper = new EdNode(subtract ? "carve" : smooth ? "blend" : "stamp");
        wrapper.children.add(document.root);
        wrapper.children.add(brushNode);
        if (smooth) wrapper.params[0] = 0.2f;
        document.root = wrapper;
    }

    private void applyPaint(SdfRaycaster.Hit hit) {
        TextureStore t = editor.texture();
        if (t == null) {
            t = makeGridTexture(64);
            editor.setTexture(t);
        }
        Vector2f uv = new Vector2f();
        Renderer.triplanarUv(hit.normal(), hit.position(), uv, (float) uvScale);
        int cx = Math.floorMod((int) Math.floor(uv.x() * t.width()), t.width());
        int cy = Math.floorMod((int) Math.floor(uv.y() * t.height()), t.height());
        t.stamp(cx, cy, (int) paintSize, toArgb(paintColor));
        refreshTextureView();
        markDirty();
    }

    private void pickColor(SdfRaycaster.Hit hit) {
        TextureStore t = editor.texture();
        if (t == null) return;
        Vector2f uv = new Vector2f();
        Renderer.triplanarUv(hit.normal(), hit.position(), uv, (float) uvScale);
        int cx = Math.floorMod((int) Math.floor(uv.x() * t.width()), t.width());
        int cy = Math.floorMod((int) Math.floor(uv.y() * t.height()), t.height());
        paintColor = toColor(t.getPixel(cx, cy));
        if (paintColorPicker != null) paintColorPicker.setValue(paintColor);
        status.setText("picked " + String.format("#%02X%02X%02X",
                (int) (paintColor.getRed() * 255), (int) (paintColor.getGreen() * 255),
                (int) (paintColor.getBlue() * 255)));
    }

    private void texturePaint(MouseEvent e) {
        TextureStore t = editor.texture();
        if (t == null) return;
        double w = textureCanvas.getWidth();
        double h = textureCanvas.getHeight();
        double scale = Math.min(w / t.width(), h / t.height());
        double ox = (w - t.width() * scale) / 2;
        double oy = (h - t.height() * scale) / 2;
        int px = (int) Math.floor((e.getX() - ox) / scale);
        int py = (int) Math.floor((e.getY() - oy) / scale);
        if (px < 0 || py < 0 || px >= t.width() || py >= t.height()) return;
        t.stamp(px, py, (int) paintSize, toArgb(paintColor));
        refreshTextureView();
        markDirty();
    }

    private void onScroll(ScrollEvent e) {
        camera.dolly((float) Math.pow(1.1, -e.getDeltaY() / 40.0));
        markDirty();
    }

    private Vector3f rayFor(MouseEvent e) {
        double W = viewport.getFitWidth();
        double H = viewport.getFitHeight();
        if (W <= 0 || H <= 0) return null;
        double scale = Math.min(W / IMG_W, H / IMG_H);
        double dw = IMG_W * scale, dh = IMG_H * scale;
        double ox = (W - dw) / 2, oy = (H - dh) / 2;
        double ix = (e.getX() - ox) / scale;
        double iy = (e.getY() - oy) / scale;
        if (ix < 0 || iy < 0 || ix >= IMG_W || iy >= IMG_H) return null;
        float u = (float) (ix / IMG_W * 2 - 1);
        float v = (float) (1 - iy / IMG_H * 2);
        return camera.ray(u, v, (float) IMG_W / IMG_H, new Vector3f());
    }

    private static int toArgb(Color c) {
        int a = (int) Math.round(c.getOpacity() * 255);
        int r = (int) Math.round(c.getRed() * 255);
        int g = (int) Math.round(c.getGreen() * 255);
        int b = (int) Math.round(c.getBlue() * 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ file I/O

    private void openModel() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SDF3D model", "*.s3d"));
        File f = fc.showOpenDialog(viewport.getScene().getWindow());
        if (f == null) return;
        try {
            String source = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            treeAuthoritative = false;
            updatingSource = true;
            sourceArea.setText(source);
            updatingSource = false;
            editor.setSource(source);
            frameCamera();
            status.setText("loaded " + f.getName() + " — " + editor.error());
            markDirty();
        } catch (IOException ex) {
            status.setText("open failed: " + ex.getMessage());
        }
    }

    private void saveModel() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SDF3D model", "*.s3d"));
        File f = fc.showSaveDialog(viewport.getScene().getWindow());
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), sourceArea.getText(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            status.setText("save failed: " + ex.getMessage());
        }
    }

    private void loadTexture() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Image", "*.png", "*.jpg", "*.bmp"));
        File f = fc.showOpenDialog(viewport.getScene().getWindow());
        if (f == null) return;
        try {
            editor.setTexture(TextureStore.load(f));
            refreshTextureView();
            markDirty();
        } catch (IOException ex) {
            status.setText("texture load failed: " + ex.getMessage());
        }
    }

    private void saveTexture() {
        TextureStore t = editor.texture();
        if (t == null) return;
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File f = fc.showSaveDialog(viewport.getScene().getWindow());
        if (f == null) return;
        try {
            t.save(f);
        } catch (IOException ex) {
            status.setText("texture save failed: " + ex.getMessage());
        }
    }
}
