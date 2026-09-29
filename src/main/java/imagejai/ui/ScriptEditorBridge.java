package imagejai.ui;

import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import javax.swing.SwingUtilities;

/** Opens macro-library entries in Fiji's optional Script Editor. */
final class ScriptEditorBridge {
    private static final String EDITOR_CLASS = "org.scijava.ui.swing.script.TextEditor";
    private static final String CONTEXT_CLASS = "org.scijava.Context";
    private static final String LEGACY_HELPER_CLASS = "net.imagej.legacy.IJ1Helper";

    private ScriptEditorBridge() {}

    static void open(MacroLibrary.MacroItem macro) throws IOException {
        if (macro == null) {
            throw new IOException("No macro selected");
        }
        try {
            Class<?> editorClass = loadClass(EDITOR_CLASS);
            Object editor = reusableEditor(editorClass);
            if (editor == null) {
                editor = createEditor(editorClass);
            }

            if (macro.path != null && Files.isRegularFile(macro.path)) {
                Method open = editorClass.getMethod("open", File.class);
                open.invoke(editor, macro.path.toFile());
            } else {
                Method create = editorClass.getMethod(
                        "createNewDocument", String.class, String.class);
                String fileName = MacroLibrary.defaultFileName(macro);
                create.invoke(editor, fileName, macro.loadCode());
                // createNewDocument sets the fallback name before it changes
                // language. Reapply the name once that language is known, so
                // EditorPane can strip its own extension from the base name.
                Object pane = editorClass.getMethod("getEditorPane").invoke(editor);
                pane.getClass().getMethod("setFileName", String.class)
                        .invoke(pane, fileName);
                Method setTitle = editorClass.getMethod("setTitle", String.class);
                setTitle.invoke(editor, "*" + fileName);
                Object openedEditor = editor;
                SwingUtilities.invokeLater(() -> {
                    try {
                        // TextEditor also queues title updates; ours must run
                        // after those callbacks to avoid name.ijm.ijm.
                        setTitle.invoke(openedEditor, "*" + fileName);
                    } catch (ReflectiveOperationException ignored) {
                        System.err.println("ImageJAI: Script Editor title update failed: "
                                + ignored.getMessage());
                    }
                });
            }

            if (editor instanceof Window) {
                Window window = (Window) editor;
                window.setVisible(true);
                window.toFront();
                window.requestFocus();
            }
        } catch (InvocationTargetException e) {
            throw editorFailure(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw editorFailure(e);
        } catch (LinkageError e) {
            throw editorFailure(e);
        }
    }

    private static Object reusableEditor(Class<?> editorClass)
            throws ReflectiveOperationException {
        Field instances = editorClass.getField("instances");
        Object value = instances.get(null);
        if (!(value instanceof List<?>)) {
            return null;
        }
        List<?> editors = (List<?>) value;
        for (int i = editors.size() - 1; i >= 0; i--) {
            Object candidate = editors.get(i);
            if (editorClass.isInstance(candidate)
                    && (!(candidate instanceof Window) || ((Window) candidate).isDisplayable())) {
                return candidate;
            }
        }
        return null;
    }

    private static Object createEditor(Class<?> editorClass)
            throws ReflectiveOperationException, IOException {
        Class<?> contextClass = loadClass(CONTEXT_CLASS);
        Class<?> legacyHelperClass = loadClass(LEGACY_HELPER_CLASS);
        Object context = legacyHelperClass.getMethod("getLegacyContext").invoke(null);
        if (context == null) {
            throw new IOException("Fiji's Script Editor context is unavailable");
        }
        return editorClass.getConstructor(contextClass).newInstance(context);
    }

    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException first) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                throw first;
            }
            return Class.forName(name, true, loader);
        }
    }

    private static IOException editorFailure(Throwable cause) {
        String detail = cause == null ? "" : cause.getMessage();
        if (cause instanceof ClassNotFoundException) {
            return new IOException("Fiji's Script Editor is not installed", cause);
        }
        if (detail == null || detail.trim().isEmpty()) {
            detail = cause == null ? "unknown error" : cause.getClass().getSimpleName();
        }
        return new IOException("Could not open Fiji's Script Editor: " + detail, cause);
    }
}
