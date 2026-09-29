package imagejai.local;

import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;
import ij.process.ByteProcessor;
import ij.measure.ResultsTable;
import imagejai.config.Settings;
import imagejai.engine.ExecutionResult;
import imagejai.engine.CommandEngine;
import imagejai.engine.MutationCoordinator;
import imagejai.engine.SessionCodeJournal;
import imagejai.engine.safeMode.DestructiveScanner;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AssistantMutationExecutorTest {

    private static final String[] MANDATORY_BOUNDARY_MACROS = new String[] {
            "call(\"java.lang.System.getProperty\", \"user.home\");",
            "text = File.openAsString(\"C:/private/subjects.csv\");"
    };

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @After
    public void clearImage() {
        WindowManager.setTempCurrentImage(null);
        ResultsTable.getResultsTable().reset();
    }

    @Test
    public void safeModeRejectsDestructiveMacroBeforeOperation() {
        Settings settings = new Settings();
        settings.safeModeEnabled = true;
        AssistantMutationExecutor executor =
                new AssistantMutationExecutor(settings, "test", "assistant-test");
        AtomicBoolean ran = new AtomicBoolean();

        ExecutionResult result = executor.executeMacro(
                "run(\"Enhance Contrast\", \"saturated=0.35 normalize\");",
                () -> {
                    ran.set(true);
                    return success();
                });

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("normalize_contrast"));
        assertFalse(ran.get());
        executor.close();
    }

    @Test
    public void safeModeOffApprovesSameMacroAndJournalsIt() {
        Settings settings = new Settings();
        settings.safeModeEnabled = false;
        AssistantMutationExecutor executor =
                new AssistantMutationExecutor(settings, "test", "assistant-test");
        String code = "run(\"Enhance Contrast\", \"saturated=0.35 normalize\");";

        ExecutionResult result = executor.executeMacro(code, () -> success());

        assertTrue(result.isSuccess());
        assertTrue(SessionCodeJournal.INSTANCE.snapshotCurrentSession().stream()
                .anyMatch(entry -> code.equals(entry.code)
                        && "assistant-test".equals(entry.source)));
        executor.close();
    }

    @Test
    public void mandatoryBoundaryCoversDirectAssistantPathWithSafeModeOnAndOff() {
        for (boolean safeMode : new boolean[] {true, false}) {
            for (String unsafeMacro : MANDATORY_BOUNDARY_MACROS) {
                AssistantMutationExecutor executor = executor(safeMode);
                AtomicBoolean ran = new AtomicBoolean();
                try {
                    ExecutionResult result = executor.executeMacro(unsafeMacro, () -> {
                        ran.set(true);
                        return success();
                    });

                    assertFalse(result.isSuccess());
                    assertMandatoryRejection(result.getError(), unsafeMacro);
                    assertFalse(ran.get());
                } finally {
                    executor.close();
                }
            }
        }
    }

    @Test
    public void mandatoryBoundaryCoversNestedAssistantPathWithSafeModeOnAndOff() {
        for (boolean safeMode : new boolean[] {true, false}) {
            for (String unsafeMacro : MANDATORY_BOUNDARY_MACROS) {
                AssistantMutationExecutor executor = executor(safeMode);
                AtomicBoolean nestedRan = new AtomicBoolean();
                try {
                    ExecutionResult result = executor.executeMacro("// outer intent", () ->
                            executor.executeMacro(unsafeMacro, () -> {
                                nestedRan.set(true);
                                return success();
                            }));

                    assertFalse(result.isSuccess());
                    assertMandatoryRejection(result.getError(), unsafeMacro);
                    assertFalse(nestedRan.get());
                } finally {
                    executor.close();
                }
            }
        }
    }

    @Test
    public void mandatoryBoundaryRechecksAssistantRetryWithSafeModeOnAndOff() {
        for (boolean safeMode : new boolean[] {true, false}) {
            for (String correctedUnsafeMacro : MANDATORY_BOUNDARY_MACROS) {
                AssistantMutationExecutor executor = executor(safeMode);
                AtomicBoolean retryRan = new AtomicBoolean();
                try {
                    ExecutionResult firstAttempt = executor.executeMacro(
                            "// original failed macro", () -> ExecutionResult.failure(
                                    "synthetic compile failure", 1L));
                    assertFalse(firstAttempt.isSuccess());

                    ExecutionResult retry = executor.executeMacro(
                            correctedUnsafeMacro, () -> {
                                retryRan.set(true);
                                return success();
                            });

                    assertFalse(retry.isSuccess());
                    assertMandatoryRejection(retry.getError(), correctedUnsafeMacro);
                    assertFalse(retryRan.get());
                } finally {
                    executor.close();
                }
            }
        }
    }

    @Test
    public void mandatoryBoundaryCoversAssistantPipelineWithSafeModeOnAndOff() {
        for (boolean safeMode : new boolean[] {true, false}) {
            for (String unsafeStep : MANDATORY_BOUNDARY_MACROS) {
                AssistantMutationExecutor executor = executor(safeMode);
                AtomicBoolean pipelineRan = new AtomicBoolean();
                try {
                    String pipelineMacro = "// Pipeline step 1\nsetMinAndMax(0, 255);\n"
                            + "// Pipeline step 2\n" + unsafeStep;
                    MutationCoordinator.Completion<Void> completion = executor.execute(
                            pipelineMacro, () -> {
                                pipelineRan.set(true);
                                return null;
                            }, 5_000L);

                    assertEquals(MutationCoordinator.State.FAILED, completion.state());
                    assertTrue(completion.error()
                            instanceof MutationCoordinator.SafetyException);
                    assertMandatoryRejection(completion.error().getMessage(), unsafeStep);
                    assertFalse(pipelineRan.get());
                } finally {
                    executor.close();
                }
            }
        }
    }

    @Test
    public void literalOutputInsideActiveAiExportsIsAllowedInBothSafeModes()
            throws Exception {
        Path imageDir = temporary.newFolder("active-image").toPath();
        ImagePlus image = new ImagePlus("file-backed", new ByteProcessor(2, 2));
        FileInfo info = new FileInfo();
        info.directory = imageDir.toString() + File.separator;
        info.fileName = "sample.tif";
        image.setFileInfo(info);
        WindowManager.setTempCurrentImage(image);
        String output = imageDir.resolve("AI_Exports").resolve("result.csv")
                .toString().replace('\\', '/');
        String macro = "File.saveString(\"result\", \"" + output + "\");";

        for (boolean safeMode : new boolean[] {true, false}) {
            AssistantMutationExecutor executor = executor(safeMode);
            AtomicBoolean ran = new AtomicBoolean();
            try {
                ExecutionResult result = executor.executeMacro(macro, () -> {
                    ran.set(true);
                    return success();
                });

                assertTrue(result.getError(), result.isSuccess());
                assertTrue(ran.get());
            } finally {
                executor.close();
            }
        }
    }

    @Test
    public void safeModeCapturesUndoBeforeApprovedMutation() {
        Settings settings = new Settings();
        settings.safeModeEnabled = true;
        ImagePlus image = new ImagePlus("assistant-undo", new ByteProcessor(2, 2));
        WindowManager.setTempCurrentImage(image);
        AssistantMutationExecutor executor =
                new AssistantMutationExecutor(settings, "test", "assistant-test");

        ExecutionResult result = executor.executeMacro(
                "setMinAndMax(0, 255); // display-only governed mutation",
                () -> success());

        assertTrue(result.isSuccess());
        assertTrue(executor.undoForTest().totalFrames() == 1);
        executor.close();
    }

    @Test
    public void approvedDestructiveMutationStillCapturesUndoWithSafeModeOff() {
        Settings settings = new Settings();
        settings.safeModeEnabled = false;
        ImagePlus image = new ImagePlus("assistant-destructive-undo",
                new ByteProcessor(2, 2));
        WindowManager.setTempCurrentImage(image);
        AssistantMutationExecutor executor =
                new AssistantMutationExecutor(settings, "test", "assistant-test");

        ExecutionResult result = executor.executeMacro("close();", () -> success());

        assertTrue(result.isSuccess());
        assertTrue(executor.undoForTest().totalFrames() == 1);
        executor.close();
    }

    @Test
    public void oversizedExactResultsUndoRejectsBeforeOperation() {
        Settings settings = new Settings();
        settings.safeModeEnabled = false;
        ImagePlus image = new ImagePlus("assistant-large-results",
                new ByteProcessor(2, 2));
        WindowManager.setTempCurrentImage(image);
        ResultsTable table = ResultsTable.getResultsTable();
        table.reset();
        table.incrementCounter();
        table.addValue("Payload", repeat('x',
                imagejai.engine.StateInspector.DEFAULT_RESULTS_CSV_LIMIT_BYTES + 100));
        AssistantMutationExecutor executor =
                new AssistantMutationExecutor(settings, "test", "assistant-test");
        AtomicBoolean ran = new AtomicBoolean();
        try {
            ExecutionResult result = executor.executeMacro(
                    "setMinAndMax(0, 255);", () -> {
                        ran.set(true);
                        return success();
                    });

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("ResultsTable undo snapshot"));
            assertFalse(ran.get());
        } finally {
            executor.close();
        }
    }

    @Test
    public void blockingMutationIsRejectedOnSwingEventThread() throws Exception {
        Settings settings = new Settings();
        AssistantMutationExecutor executor =
                new AssistantMutationExecutor(settings, "test", "assistant-test");
        AtomicReference<ExecutionResult> result = new AtomicReference<ExecutionResult>();

        SwingUtilities.invokeAndWait(() -> result.set(executor.executeMacro(
                "setMinAndMax(0, 255); // must not run on Swing thread",
                () -> success())));

        assertFalse(result.get().isSuccess());
        assertTrue(result.get().getError().contains("Swing event thread"));
        executor.close();
    }

    @Test
    public void fijiBridgeUsesGovernedBoundaryForLocalAssistantMacros() {
        RecordingCommandEngine command = new RecordingCommandEngine();
        FijiBridge bridge = new FijiBridge(command);
        Settings settings = new Settings();
        settings.safeModeEnabled = true;
        bridge.configureGovernance(settings);

        boolean blocked = false;
        try {
            bridge.runMacro("run(\"Enhance Contrast\", \"normalize\");");
        } catch (IllegalStateException expected) {
            blocked = true;
        }
        assertTrue(blocked);
        assertFalse(command.ran.get());

        settings.safeModeEnabled = false;
        bridge.runMacro("run(\"Enhance Contrast\", \"normalize\");");
        assertTrue(command.ran.get());
    }

    @Test
    public void localDestructiveIntentNeedsSafeModeApprovalAndRunsOnceWhenApproved() {
        Settings settings = new Settings();
        settings.safeModeEnabled = true;
        FijiBridge bridge = new FijiBridge(new RecordingCommandEngine());
        bridge.configureGovernance(settings);
        AtomicBoolean ran = new AtomicBoolean();

        AssistantReply denied = bridge.executeIntent("image.close_active", () -> {
            ran.set(true);
            return AssistantReply.text("closed");
        });

        assertTrue(denied.text().startsWith("Action blocked or failed:"));
        assertFalse(ran.get());

        settings.safeModeEnabled = false;
        AssistantReply approved = bridge.executeIntent("image.close_active", () -> {
            if (!ran.compareAndSet(false, true)) {
                throw new AssertionError("operation ran more than once");
            }
            return AssistantReply.text("closed");
        });
        assertTrue(ran.get());
        assertTrue("closed".equals(approved.text()));
    }

    @Test
    public void fijiBridgeSubclassCannotBypassDestructiveApproval() {
        Settings settings = new Settings();
        settings.safeModeEnabled = true;
        FijiBridge bridge = new FijiBridge(null) { };
        bridge.configureGovernance(settings);
        AtomicBoolean ran = new AtomicBoolean();

        AssistantReply denied = bridge.executeIntent("image.close_active", () -> {
            ran.set(true);
            return AssistantReply.text("closed");
        });

        assertTrue(denied.text().startsWith("Action blocked or failed:"));
        assertFalse(ran.get());
    }

    private static ExecutionResult success() {
        return ExecutionResult.success("", null,
                Collections.<String>emptyList(), 1L);
    }

    private static AssistantMutationExecutor executor(boolean safeMode) {
        Settings settings = new Settings();
        settings.safeModeEnabled = safeMode;
        return new AssistantMutationExecutor(settings, "test", "assistant-test");
    }

    private static void assertMandatoryRejection(String message, String macro) {
        String expectedRule = macro.contains("File.")
                ? DestructiveScanner.RULE_MACRO_FILESYSTEM
                : DestructiveScanner.RULE_HOST_CODE;
        assertTrue("expected " + expectedRule + " in: " + message,
                message != null && message.contains(expectedRule));
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    private static final class RecordingCommandEngine extends CommandEngine {
        final AtomicBoolean ran = new AtomicBoolean();

        @Override
        public ExecutionResult executeMacro(String code) {
            ran.set(true);
            return success();
        }
    }
}
