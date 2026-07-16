package imagejai.local;

import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import imagejai.config.Settings;
import imagejai.engine.ExecutionResult;
import imagejai.engine.ImageGraph;
import imagejai.engine.MutationCoordinator;
import imagejai.engine.SessionCodeJournal;
import imagejai.engine.SessionUndo;
import imagejai.engine.StateInspector;
import imagejai.engine.UndoFrame;
import imagejai.engine.safeMode.DestructiveScanner;
import imagejai.engine.safeMode.RoiAutoBackup;

import javax.swing.SwingUtilities;
import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared governance boundary for mutations initiated by the two in-process
 * assistant surfaces. It deliberately uses the same scanner, undo frame,
 * provenance graph, journal, and coordinator lifecycle as TCP mutations.
 */
public final class AssistantMutationExecutor implements AutoCloseable {

    private static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private final Settings settings;
    private final String ownerSession;
    private final String source;
    private final MutationCoordinator coordinator;
    private final SessionUndo undo = new SessionUndo();
    private final ImageGraph graph = new ImageGraph();
    private final StateInspector inspector = new StateInspector();
    private final AtomicLong callSequence = new AtomicLong();
    private final AtomicReference<MutationCoordinator.Handle<?>> active =
            new AtomicReference<MutationCoordinator.Handle<?>>();
    private final ThreadLocal<Boolean> insideMutation = new ThreadLocal<Boolean>();
    private final boolean ownsCoordinator;

    public AssistantMutationExecutor(Settings settings, String ownerSession,
                                     String source) {
        this(settings, ownerSession, source, new MutationCoordinator(), true);
    }

    public AssistantMutationExecutor(Settings settings, String ownerSession,
                                     String source, MutationCoordinator coordinator) {
        this(settings, ownerSession, source, coordinator, false);
    }

    AssistantMutationExecutor(Settings settings, String ownerSession, String source,
                              MutationCoordinator coordinator, boolean ownsCoordinator) {
        this.settings = settings == null ? new Settings() : settings;
        this.ownerSession = textOr(ownerSession, "assistant");
        this.source = textOr(source, "assistant");
        this.coordinator = coordinator == null ? new MutationCoordinator() : coordinator;
        this.ownsCoordinator = ownsCoordinator;
    }

    public ExecutionResult executeMacro(final String code,
                                        final MutationCoordinator.Operation<ExecutionResult> operation) {
        if (code == null || code.trim().isEmpty()) {
            return ExecutionResult.failure("Empty macro code", 0L);
        }
        if (Boolean.TRUE.equals(insideMutation.get())) {
            long started = System.currentTimeMillis();
            SessionCodeJournal.DatasetBinding journalDataset =
                    SessionCodeJournal.captureInitiatingDataset();
            try {
                if (settings.safeModeEnabled) enforceSafety(code, false);
                ExecutionResult result = operation.run();
                journalNested(journalDataset, code, started, result != null && result.isSuccess(),
                        result == null ? "No execution result" : result.getError());
                return result == null
                        ? ExecutionResult.failure("No execution result", 0L) : result;
            } catch (Exception e) {
                journalNested(journalDataset, code, started, false, e.getMessage());
                return ExecutionResult.failure(e.getMessage(),
                        System.currentTimeMillis() - started);
            }
        }
        MutationCoordinator.Completion<ExecutionResult> completion;
        try {
            completion = execute(code, operation, DEFAULT_TIMEOUT_MS);
        } catch (RuntimeException e) {
            return ExecutionResult.failure(e.getMessage(), 0L);
        }
        if (completion.state() == MutationCoordinator.State.SUCCEEDED
                && completion.result() != null) {
            return completion.result();
        }
        String message = completion.error() == null
                ? completion.state().name().toLowerCase()
                : completion.error().getMessage();
        return ExecutionResult.failure(message == null ? "Mutation failed" : message,
                completion.elapsedMs());
    }

    public <T> MutationCoordinator.Completion<T> execute(
            final String code,
            final MutationCoordinator.Operation<T> operation,
            long timeoutMs) {
        return execute(code, operation, timeoutMs, false);
    }

    public <T> MutationCoordinator.Completion<T> execute(
            final String code,
            final MutationCoordinator.Operation<T> operation,
            long timeoutMs,
            final boolean explicitApprovalRequired) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Assistant mutation cannot block the Swing event thread.");
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation is required");
        }
        final boolean safeMode = settings.safeModeEnabled;
        final String callId = ownerSession + "-" + callSequence.incrementAndGet();
        final String mutationCode = code == null ? "" : code;
        final ImagePlus imageAtAdmission = WindowManager.getCurrentImage();
        final SessionCodeJournal.DatasetBinding journalDataset =
                SessionCodeJournal.captureInitiatingDataset();

        MutationCoordinator.Lifecycle<T> lifecycle = new MutationCoordinator.Lifecycle<T>() {
            private Set<String> titlesBefore;
            private String activeTitleBefore;
            private long graphMarker;
            private boolean prepared;

            @Override
            public void checkSafety() throws Exception {
                enforceSafety(mutationCode, explicitApprovalRequired);
            }

            @Override
            public void beforeMutation() {
                titlesBefore = ImageGraph.captureOpenTitles();
                activeTitleBefore = ImageGraph.captureActiveTitle();
                graphMarker = graph.currentMarker();
                prepared = true;
                captureUndo(callId, mutationCode, imageAtAdmission);
            }

            @Override
            public void afterMutation(MutationCoordinator.Outcome<T> outcome) {
                if (!prepared) return;
                graph.trackMacroChange(titlesBefore, activeTitleBefore,
                        ImageGraph.captureOpenTitles(), mutationCode, source);
                graph.deltaSince(graphMarker);
            }

            @Override
            public void onCompletion(MutationCoordinator.Completion<T> completion) {
                boolean success = completion.state() == MutationCoordinator.State.SUCCEEDED;
                if (completion.result() instanceof ExecutionResult) {
                    success = success && ((ExecutionResult) completion.result()).isSuccess();
                }
                String failure = completion.error() == null
                        ? null : completion.error().getMessage();
                SessionCodeJournal.INSTANCE.record(journalDataset, "ijm", mutationCode, source, 0L,
                        completion.startedAtMs(), completion.elapsedMs(), success, failure);
            }
        };

        MutationCoordinator.Request<T> request = MutationCoordinator.Request.<T>builder()
                .ownerSession(ownerSession)
                .sourceKind(source)
                .code(mutationCode)
                .timeoutMs(Math.max(0L, timeoutMs))
                .safetyEnabled(safeMode)
                .undoEnabled(true)
                .provenanceEnabled(true)
                .operation(new MutationCoordinator.Operation<T>() {
                    @Override
                    public T run() throws Exception {
                        insideMutation.set(Boolean.TRUE);
                        try {
                            return operation.run();
                        } finally {
                            insideMutation.remove();
                        }
                    }
                })
                .lifecycle(lifecycle)
                .build();

        final MutationCoordinator.Handle<T> handle;
        try {
            handle = coordinator.submit(request, h -> active.set(h));
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("Mutation admission rejected: " + e.getMessage(), e);
        }
        boolean interrupted = false;
        try {
            MutationCoordinator.Completion<T> completion;
            while (true) {
                try {
                    completion = handle.awaitCompletion();
                    return completion;
                } catch (InterruptedException e) {
                    interrupted = true;
                    handle.cancel();
                }
            }
        } finally {
            active.compareAndSet(handle, null);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public void cancelActive() {
        MutationCoordinator.Handle<?> handle = active.get();
        if (handle != null) handle.cancel();
    }

    SessionUndo undoForTest() {
        return undo;
    }

    ImageGraph graphForTest() {
        return graph;
    }

    MutationCoordinator coordinatorForTest() {
        return coordinator;
    }

    private void captureUndo(String callId, String code, ImagePlus fallbackImage) {
        try {
            ImagePlus image = WindowManager.getCurrentImage();
            if (image == null) image = fallbackImage;
            if (image == null) return;
            UndoFrame frame = UndoFrame.capture(callId, image, RoiManager.getInstance(),
                    inspector.getResultsTableCSV(), DestructiveScanner.hasDiskWrites(code));
            undo.pushFrame(frame);
        } catch (Throwable ignored) {
            // Undo is best effort and must never prevent the governed mutation.
        }
    }

    private void enforceSafety(String code, boolean explicitApprovalRequired)
            throws Exception {
        if (explicitApprovalRequired) {
            throw new MutationCoordinator.SafetyException(
                    "Action requires approval: disable Safe Mode for this intentional run.");
        }
        List<DestructiveScanner.DestructiveOp> findings =
                DestructiveScanner.scan(code, scannerContext());
        List<DestructiveScanner.DestructiveOp> rejected =
                DestructiveScanner.rejections(findings);
        if (!rejected.isEmpty()) {
            StringBuilder message = new StringBuilder(
                    "Macro blocked by safe-mode scanner: ");
            for (int i = 0; i < rejected.size(); i++) {
                if (i > 0) message.append("; ");
                DestructiveScanner.DestructiveOp op = rejected.get(i);
                message.append(op.ruleId).append(" @ line ").append(op.line);
            }
            throw new MutationCoordinator.SafetyException(message.toString());
        }
        for (DestructiveScanner.DestructiveOp op : DestructiveScanner.backups(findings)) {
            if (DestructiveScanner.RULE_ROI_WIPE.equals(op.ruleId)) {
                RoiAutoBackup.backup(RoiManager.getInstance(),
                        WindowManager.getCurrentImage());
            }
        }
    }

    private void journalNested(SessionCodeJournal.DatasetBinding dataset, String code,
                               long started, boolean success, String failure) {
        SessionCodeJournal.INSTANCE.record(dataset, "ijm", code, source, 0L, started,
                System.currentTimeMillis() - started, success, failure);
    }

    boolean isInsideMutation() {
        return Boolean.TRUE.equals(insideMutation.get());
    }

    private DestructiveScanner.Context scannerContext() {
        String activePath = null;
        String exportsRoot = null;
        int bitDepth = 0;
        boolean calibrated = false;
        int roiCount = 0;
        int resultRows = 0;
        try {
            ImagePlus image = WindowManager.getCurrentImage();
            if (image != null) {
                bitDepth = image.getBitDepth();
                ij.io.FileInfo info = image.getOriginalFileInfo();
                if (info != null && info.directory != null && info.fileName != null) {
                    activePath = info.directory + info.fileName;
                    exportsRoot = new File(info.directory, "AI_Exports").getPath();
                }
                ij.measure.Calibration calibration = image.getCalibration();
                if (calibration != null) {
                    String unit = calibration.getUnit();
                    calibrated = Math.abs(calibration.pixelWidth - 1.0) > 1e-9
                            || (unit != null && !unit.isEmpty()
                            && !"pixel".equalsIgnoreCase(unit)
                            && !"pixels".equalsIgnoreCase(unit));
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            RoiManager manager = RoiManager.getInstance();
            if (manager != null) roiCount = manager.getCount();
        } catch (Throwable ignored) {
        }
        try {
            ResultsTable table = ResultsTable.getResultsTable();
            if (table != null) resultRows = table.getCounter();
        } catch (Throwable ignored) {
        }
        return new DestructiveScanner.Context(activePath, exportsRoot, bitDepth,
                calibrated, roiCount, resultRows,
                true, true, path -> {
                    try {
                        return path != null && java.nio.file.Files.exists(
                                java.nio.file.Paths.get(path));
                    } catch (Throwable ignored) {
                        return false;
                    }
                });
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    @Override
    public void close() {
        cancelActive();
        if (ownsCoordinator) {
            coordinator.shutdown();
        }
    }
}
