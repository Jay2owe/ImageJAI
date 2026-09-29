package imagejai.engine.automation;

import com.google.gson.JsonObject;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.process.ByteProcessor;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import java.awt.GraphicsEnvironment;
import java.awt.Point;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * The component model: classification, exact label resolution, masking, and the
 * action set. These are the attributes a scenario matches on, so a wrong answer
 * here means a harness drives the wrong control.
 */
public class UiNodeTest {

    private static UiNode node(java.awt.Component component) {
        return new UiNode(component, "n-test-1", "w-test-1", null, "0", 1, 7L,
                new Point(0, 0));
    }

    @Test
    public void radioButtonsAreNotClassifiedAsGenericToggles() {
        // JCheckBox and JRadioButton both extend JToggleButton. The legacy flat
        // inventory tested JToggleButton before JRadioButton and reported every
        // GenericDialog radio group as "toggle".
        assertEquals(UiNode.ROLE_RADIO, UiNode.roleOf(new JRadioButton("Mean")));
        assertEquals(UiNode.ROLE_CHECKBOX, UiNode.roleOf(new JCheckBox("Preview")));
        assertEquals(UiNode.ROLE_TOGGLE, UiNode.roleOf(new JToggleButton("Pin")));
        assertEquals(UiNode.ROLE_BUTTON, UiNode.roleOf(new JButton("OK")));
    }

    @Test
    public void awtCheckboxInAGroupIsARadioButton() {
        assumeFalse("AWT peers unavailable", GraphicsEnvironment.isHeadless());
        java.awt.CheckboxGroup group = new java.awt.CheckboxGroup();
        java.awt.Checkbox grouped = new java.awt.Checkbox("Otsu", group, true);
        java.awt.Checkbox standalone = new java.awt.Checkbox("Dark background");

        // ImageJ's GenericDialog.addRadioButtonGroup builds exactly this shape.
        assertEquals(UiNode.ROLE_RADIO, UiNode.roleOf(grouped));
        assertEquals(UiNode.ROLE_CHECKBOX, UiNode.roleOf(standalone));
    }

    @Test
    public void moreRolesAreClassifiedExactly() {
        assertEquals(UiNode.ROLE_TEXT, UiNode.roleOf(new JTextField()));
        assertEquals(UiNode.ROLE_PASSWORD, UiNode.roleOf(new JPasswordField()));
        assertEquals(UiNode.ROLE_TEXTAREA, UiNode.roleOf(new javax.swing.JTextArea()));
        assertEquals(UiNode.ROLE_COMBO, UiNode.roleOf(new JComboBox<String>()));
        assertEquals(UiNode.ROLE_SLIDER, UiNode.roleOf(new JSlider()));
        assertEquals(UiNode.ROLE_SPINNER, UiNode.roleOf(new javax.swing.JSpinner()));
        assertEquals(UiNode.ROLE_TABS, UiNode.roleOf(new JTabbedPane()));
        assertEquals(UiNode.ROLE_TABLE, UiNode.roleOf(new javax.swing.JTable()));
        assertEquals(UiNode.ROLE_TREE, UiNode.roleOf(new javax.swing.JTree()));
        assertEquals(UiNode.ROLE_LABEL, UiNode.roleOf(new JLabel("Sigma")));
        assertEquals(UiNode.ROLE_MENU, UiNode.roleOf(new javax.swing.JMenu("File")));
        assertEquals(UiNode.ROLE_MENU_ITEM,
                UiNode.roleOf(new javax.swing.JMenuItem("Open...")));
        assertEquals(UiNode.ROLE_PANEL, UiNode.roleOf(new JPanel()));
        assertEquals(UiNode.ROLE_OTHER, UiNode.roleOf(null));
    }

    @Test
    public void imageCanvasPublishesItsCurrentViewportTransform() {
        ImagePlus image = new ImagePlus("canvas-test", new ByteProcessor(256, 192));
        ImageCanvas canvas = new ImageCanvas(image);
        canvas.setSize(128, 96);

        JsonObject json = node(canvas).toJson(false);
        assertEquals(UiNode.ROLE_IMAGE_CANVAS, json.get("role").getAsString());
        JsonObject view = json.getAsJsonObject("canvas_view");
        assertEquals(256, view.get("image_width").getAsInt());
        assertEquals(192, view.get("image_height").getAsInt());
        assertEquals(canvas.getSrcRect().x, view.get("source_x").getAsInt());
        assertEquals(canvas.getSrcRect().y, view.get("source_y").getAsInt());
        assertEquals(canvas.getSrcRect().width, view.get("source_width").getAsInt());
        assertEquals(canvas.getSrcRect().height, view.get("source_height").getAsInt());
        assertEquals(canvas.getMagnification(), view.get("magnification").getAsDouble(), 0.0d);
        assertEquals(128, view.get("canvas_width").getAsInt());
        assertEquals(96, view.get("canvas_height").getAsInt());
    }

    @Test
    public void labelForBeatsProximityAndTheSourceIsReported() {
        JPanel panel = new JPanel();
        JTextField sigma = new JTextField("2.0");
        JLabel decoy = new JLabel("Radius");
        JLabel real = new JLabel("Sigma");
        real.setLabelFor(sigma);
        panel.add(decoy);
        panel.add(real);
        panel.add(sigma);

        JsonObject json = node(sigma).toJson(false);
        assertEquals("Sigma", json.get("label").getAsString());
        assertEquals("label_for", json.get("label_source").getAsString());
    }

    @Test
    public void proximityIsUsedOnlyWhenNothingExactExists() {
        JPanel panel = new JPanel();
        JLabel caption = new JLabel("Threshold");
        JTextField field = new JTextField("128");
        panel.add(caption);
        panel.add(field);

        JsonObject json = node(field).toJson(false);
        assertEquals("Threshold", json.get("label").getAsString());
        assertEquals("nearest_label", json.get("label_source").getAsString());
    }

    @Test
    public void accessibleNameOutranksNearestLabel() {
        JPanel panel = new JPanel();
        panel.add(new JLabel("Decoy"));
        JButton button = new JButton();
        button.getAccessibleContext().setAccessibleName("Run analysis");
        panel.add(button);

        JsonObject json = node(button).toJson(false);
        assertEquals("Run analysis", json.get("label").getAsString());
        assertEquals("accessible_name", json.get("label_source").getAsString());
    }

    @Test
    public void passwordFieldsAreMaskedAndOfferNoActions() {
        JPasswordField password = new JPasswordField("hunter2-secret");
        JsonObject json = node(password).toJson(false);

        assertTrue(json.get("masked").getAsBoolean());
        assertFalse(json.has("value"));
        assertFalse(json.has("text"));
        assertEquals(0, json.getAsJsonArray("actions").size());
        assertFalse(json.toString().contains("hunter2"));
    }

    @Test
    public void credentialShapedFieldsAreMaskedByNameOrLabel() {
        JTextField byName = new JTextField("sk-live-abcdefg");
        byName.setName("anthropicApiKey");
        assertTrue(UiNode.isMasked(byName));
        assertFalse(node(byName).toJson(false).toString().contains("sk-live"));

        JPanel panel = new JPanel();
        panel.add(new JLabel("API key"));
        JTextField byLabel = new JTextField("sk-live-hijklmn");
        panel.add(byLabel);
        assertTrue(UiNode.isMasked(byLabel));

        JTextField opted = new JTextField("value");
        opted.putClientProperty("imagejai.automation.secret", Boolean.TRUE);
        assertTrue(UiNode.isMasked(opted));

        JTextField ordinary = new JTextField("2.5");
        assertFalse(UiNode.isMasked(ordinary));
        assertEquals("2.5", node(ordinary).toJson(false).get("value").getAsString());

        // Masking is for value-bearing controls; a button whose caption happens
        // to mention a token still needs to be clickable.
        assertFalse(UiNode.isMasked(new JButton("Paste API key")));
    }

    @Test
    public void maskedFieldsReportOnlyALength() {
        // Enough for a scenario to assert "the field is populated" without the
        // bridge ever copying the characters out of the JVM.
        JPasswordField password = new JPasswordField();
        password.setText("abcdefgh");
        JsonObject json = node(password).toJson(false);
        assertTrue(json.get("masked").getAsBoolean());
        assertEquals(8, json.get("masked_length").getAsInt());
        assertFalse(json.toString().contains("abcdefgh"));
    }

    @Test
    public void disabledControlsAdvertiseNoActions() {
        JButton button = new JButton("OK");
        assertTrue(node(button).toJson(false).getAsJsonArray("actions").size() > 0);
        button.setEnabled(false);
        assertEquals(0, node(button).toJson(false).getAsJsonArray("actions").size());
    }

    @Test
    public void actionSetsMatchTheControlKind() {
        assertTrue(UiNode.actionsFor(UiNode.ROLE_BUTTON, new JButton("OK"))
                .contains(UiNode.ACTION_ACTIVATE));
        assertTrue(UiNode.actionsFor(UiNode.ROLE_CHECKBOX, new JCheckBox("Preview"))
                .contains(UiNode.ACTION_SET_SELECTED));
        assertTrue(UiNode.actionsFor(UiNode.ROLE_TEXT, new JTextField())
                .contains(UiNode.ACTION_SET_TEXT));
        assertTrue(UiNode.actionsFor(UiNode.ROLE_SLIDER, new JSlider())
                .contains(UiNode.ACTION_SET_NUMBER));
        assertTrue(UiNode.actionsFor(UiNode.ROLE_TABS, new JTabbedPane())
                .contains(UiNode.ACTION_SELECT_TAB));
        assertTrue(UiNode.actionsFor(UiNode.ROLE_COMBO, new JComboBox<String>())
                .contains(UiNode.ACTION_SELECT_ITEM));
        // No role ever advertises a physical gesture: that channel is the
        // external harness's, and conflating them would let a report claim a
        // real click happened when only a listener ran.
        for (String role : new String[] {UiNode.ROLE_BUTTON, UiNode.ROLE_TEXT,
                UiNode.ROLE_SLIDER, UiNode.ROLE_CANVAS, UiNode.ROLE_IMAGE_CANVAS}) {
            for (String action : UiNode.actionsFor(role, new JButton("x"))) {
                assertFalse(role + "/" + action, action.contains("click"));
                assertFalse(role + "/" + action, action.contains("type"));
                assertFalse(role + "/" + action, action.contains("move"));
            }
        }
    }

    @Test
    public void enumeratedChoicesAreBoundedAndReportTruncation() {
        JComboBox<String> combo = new JComboBox<String>();
        for (int i = 0; i < AutomationPolicy.MAX_ITEMS_PER_NODE + 10; i++) {
            combo.addItem("method-" + i);
        }
        combo.setSelectedIndex(3);

        JsonObject json = node(combo).toJson(false);
        assertEquals(AutomationPolicy.MAX_ITEMS_PER_NODE,
                json.getAsJsonArray("items").size());
        assertEquals(AutomationPolicy.MAX_ITEMS_PER_NODE + 10,
                json.get("item_count").getAsInt());
        assertTrue(json.get("items_truncated").getAsBoolean());
        assertEquals(3, json.get("selected_index").getAsInt());
        assertEquals("method-3", json.get("value").getAsString());
    }

    @Test
    public void tablesAndTreesPublishTheSelectedRowForPhysicalProof() {
        javax.swing.JTable table = new javax.swing.JTable(
                new String[][] {{"Alpha"}, {"Beta"}, {"Gamma"}},
                new String[] {"Name"});
        table.setRowSelectionInterval(1, 1);
        JsonObject tableJson = node(table).toJson(false);
        assertEquals(1, tableJson.get("selected_index").getAsInt());
        assertEquals(3, tableJson.get("row_count").getAsInt());
        assertEquals(1, tableJson.get("column_count").getAsInt());

        javax.swing.JTree tree = new javax.swing.JTree();
        tree.setSelectionRow(2);
        JsonObject treeJson = node(tree).toJson(false);
        assertEquals(2, treeJson.get("selected_index").getAsInt());
        assertEquals(tree.getRowCount(), treeJson.get("row_count").getAsInt());
        assertEquals(1, treeJson.get("column_count").getAsInt());
    }

    @Test
    public void longTextIsBoundedBeforeItReachesAResponse() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < AutomationPolicy.MAX_TEXT_CHARS * 3; i++) huge.append('x');
        JTextField field = new JTextField(huge.toString());
        assertEquals(AutomationPolicy.MAX_TEXT_CHARS,
                node(field).toJson(false).get("value").getAsString().length());
    }

    @Test
    public void numericControlsExposeTheirRange() {
        JSlider slider = new JSlider(0, 255, 128);
        JsonObject numeric = node(slider).toJson(false).getAsJsonObject("numeric");
        assertEquals(128.0d, numeric.get("value").getAsDouble(), 0.0d);
        assertEquals(0.0d, numeric.get("minimum").getAsDouble(), 0.0d);
        assertEquals(255.0d, numeric.get("maximum").getAsDouble(), 0.0d);
    }

    @Test
    public void aSlidersPositionIsAlsoReadableAsAPlainValue() {
        // `numeric` stays the authoritative, typed form — this only mirrors it,
        // so that "what does this control currently say" has an answer for
        // every control set_number can drive. A client reading one field found
        // null for all four sliders in ImageJ's B&C window, which reads as "the
        // bridge cannot see this control".
        assertEquals("128", node(new JSlider(0, 255, 128))
                .toJson(false).get("value").getAsString());
        assertEquals("96", node(new javax.swing.JScrollBar(
                javax.swing.JScrollBar.HORIZONTAL, 96, 1, 0, 256))
                .toJson(false).get("value").getAsString());
    }

    @Test
    public void anAwtScrollbarReportsItsPositionToo() {
        // ImageJ's B&C sliders are java.awt.Scrollbar, not the Swing class.
        assumeFalse(GraphicsEnvironment.isHeadless());
        java.awt.Scrollbar bar =
                new java.awt.Scrollbar(java.awt.Scrollbar.HORIZONTAL, 96, 1, 0, 256);
        JsonObject json = node(bar).toJson(false);
        assertEquals("96", json.get("value").getAsString());
        assertEquals(96.0d, json.getAsJsonObject("numeric").get("value").getAsDouble(),
                0.0d);
    }

    @Test
    public void mirroringAPositionDoesNotGiveAPlainButtonAValue() {
        // The mirror is for controls whose state *is* a number. Everything else
        // must still report no value at all rather than an empty string.
        assertFalse(node(new JButton("Auto")).toJson(false).has("value"));
        assertFalse(node(new JPanel()).toJson(false).has("value"));
    }

    @Test
    public void screenGeometryIsOmittedWhenTheComponentIsNotShowing() {
        JsonObject json = node(new JButton("OK")).toJson(false);
        assertTrue(json.has("bounds"));
        assertFalse("screen coordinates are undefined for a component that is "
                + "not on screen", json.has("screen_bounds"));
        assertFalse(json.has("screen_center"));
    }

    @Test
    public void semanticDiffReportsOnlyWhatChanged() {
        JCheckBox checkbox = new JCheckBox("Preview");
        JsonObject before = node(checkbox).semanticJson();
        checkbox.setSelected(true);
        JsonObject after = node(checkbox).semanticJson();

        JsonObject delta = UiNode.diff(before, after);
        assertEquals(1, delta.size());
        assertFalse(delta.getAsJsonObject("selected").get("from").getAsBoolean());
        assertTrue(delta.getAsJsonObject("selected").get("to").getAsBoolean());

        assertEquals(0, UiNode.diff(after, after).size());
        assertEquals(0, UiNode.diff(null, after).size());
    }

    @Test
    public void nodeIdentityAndHierarchyAreCarriedOnEveryNode() {
        JsonObject json = node(new JButton("OK")).toJson(false);
        assertEquals("n-test-1", json.get("id").getAsString());
        assertEquals("w-test-1", json.get("window_id").getAsString());
        assertEquals(7L, json.get("generation").getAsLong());
        assertEquals("0", json.get("index_path").getAsString());
        assertEquals(1, json.get("depth").getAsInt());
        assertNull(json.get("parent_id"));
    }
}
