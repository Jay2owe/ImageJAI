package imagejai.engine;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Owns the complete lifecycle of work that can mutate ImageJ's global state.
 *
 * <p>Admission is bounded before a worker is created. Every admitted worker
 * enters one shared mutation monitor, and that monitor plus the admission
 * permit remain owned until the worker's {@code finally} block has run. A
 * separate observer joins the dedicated worker thread before publishing a
 * terminal state; {@link java.util.concurrent.Future#isDone()} is never used
 * as evidence that ImageJ code has stopped.</p>
 */
public final class MutationCoordinator implements AutoCloseable {

    public static final int DEFAULT_CAPACITY = 16;

    public enum State {
        ADMITTED,
        RUNNING,
        CANCEL_REQUESTED,
        TIMEOUT_REQUESTED,
        WORKER_EXITED,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }

    private enum CancelReason { USER, TIMEOUT, SHUTDOWN }

    @FunctionalInterface
    public interface Operation<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    public interface CancellationAction {
        void cancel() throws Exception;
    }

    /**
     * Central policy hooks. Safety runs inside the mutation monitor before
     * undo capture and execution. Provenance runs once after the operation,
     * including failure/cancellation. Completion runs once after thread exit.
     */
    public interface Lifecycle<T> {
        default void checkSafety() throws Exception {}
        default void beforeMutation() throws Exception {}
        default void afterMutation(Outcome<T> outcome) throws Exception {}
        default void onCompletion(Completion<T> completion) {}
    }

    public static class SafetyException extends Exception {
        public SafetyException(String message) {
            super(message);
        }
    }

    public static final class Outcome<T> {
        private final T result;
        private final Throwable error;
        private final boolean cancellationRequested;

        Outcome(T result, Throwable error, boolean cancellationRequested) {
            this.result = result;
            this.error = error;
            this.cancellationRequested = cancellationRequested;
        }

        public T result() { return result; }
        public Throwable error() { return error; }
        public boolean cancellationRequested() { return cancellationRequested; }
        public boolean succeeded() {
            return error == null && !cancellationRequested;
        }
    }

    public static final class Completion<T> {
        private final State state;
        private final T result;
        private final Throwable error;
        private final long startedAtMs;
        private final long endedAtMs;

        Completion(State state, T result, Throwable error,
                   long startedAtMs, long endedAtMs) {
            this.state = state;
            this.result = result;
            this.error = error;
            this.startedAtMs = startedAtMs;
            this.endedAtMs = endedAtMs;
        }

        public State state() { return state; }
        public T result() { return result; }
        public Throwable error() { return error; }
        public long startedAtMs() { return startedAtMs; }
        public long endedAtMs() { return endedAtMs; }
        public long elapsedMs() { return Math.max(0L, endedAtMs - startedAtMs); }
    }

    public static final class Request<T> {
        private final String ownerSession;
        private final String sourceKind;
        private final String code;
        private final long timeoutMs;
        private final boolean safetyEnabled;
        private final boolean undoEnabled;
        private final boolean provenanceEnabled;
        private final Operation<T> operation;
        private final CancellationAction cancellationAction;
        private final Lifecycle<T> lifecycle;

        private Request(Builder<T> b) {
            this.ownerSession = requireText(b.ownerSession, "ownerSession");
            this.sourceKind = requireText(b.sourceKind, "sourceKind");
            this.code = b.code == null ? "" : b.code;
            this.timeoutMs = b.timeoutMs;
            this.safetyEnabled = b.safetyEnabled;
            this.undoEnabled = b.undoEnabled;
            this.provenanceEnabled = b.provenanceEnabled;
            if (b.operation == null) throw new IllegalArgumentException("operation is required");
            this.operation = b.operation;
            this.cancellationAction = b.cancellationAction;
            this.lifecycle = b.lifecycle == null ? new Lifecycle<T>() {} : b.lifecycle;
        }

        public String ownerSession() { return ownerSession; }
        public String sourceKind() { return sourceKind; }
        public String code() { return code; }
        public long timeoutMs() { return timeoutMs; }
        public boolean safetyEnabled() { return safetyEnabled; }
        public boolean undoEnabled() { return undoEnabled; }
        public boolean provenanceEnabled() { return provenanceEnabled; }

        public static <T> Builder<T> builder() { return new Builder<T>(); }

        public static final class Builder<T> {
            private String ownerSession;
            private String sourceKind = "mutation";
            private String code = "";
            private long timeoutMs;
            private boolean safetyEnabled;
            private boolean undoEnabled;
            private boolean provenanceEnabled;
            private Operation<T> operation;
            private CancellationAction cancellationAction;
            private Lifecycle<T> lifecycle;

            public Builder<T> ownerSession(String value) { ownerSession = value; return this; }
            public Builder<T> sourceKind(String value) { sourceKind = value; return this; }
            public Builder<T> code(String value) { code = value; return this; }
            public Builder<T> timeoutMs(long value) { timeoutMs = value; return this; }
            public Builder<T> safetyEnabled(boolean value) { safetyEnabled = value; return this; }
            public Builder<T> undoEnabled(boolean value) { undoEnabled = value; return this; }
            public Builder<T> provenanceEnabled(boolean value) { provenanceEnabled = value; return this; }
            public Builder<T> operation(Operation<T> value) { operation = value; return this; }
            public Builder<T> cancellationAction(CancellationAction value) {
                cancellationAction = value;
                return this;
            }
            public Builder<T> lifecycle(Lifecycle<T> value) { lifecycle = value; return this; }
            public Request<T> build() { return new Request<T>(this); }
        }
    }

    public static final class Handle<T> {
        private final MutationCoordinator coordinator;
        private final String id;
        private final Request<T> request;
        private final long startedAtMs;
        private final boolean delegatedMonitorOwnership;
        private final CountDownLatch workerFinalized = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final AtomicReference<CancelReason> cancelReason =
                new AtomicReference<CancelReason>();
        private final AtomicBoolean cancellationActionCalled = new AtomicBoolean(false);
        private final AtomicBoolean completionCalled = new AtomicBoolean(false);
        private volatile Thread worker;
        private volatile boolean operationActive;
        private volatile boolean cancellationTargetActive;
        private volatile boolean mutationBodyFinished;
        private volatile boolean operationFinished;
        private volatile boolean workerExited;
        private volatile T result;
        private volatile Throwable error;
        private volatile State terminalState;
        private volatile long endedAtMs;
        private volatile ScheduledFuture<?> timeoutFuture;

        Handle(MutationCoordinator coordinator, String id, Request<T> request,
               long startedAtMs, boolean delegatedMonitorOwnership) {
            this.coordinator = coordinator;
            this.id = id;
            this.request = request;
            this.startedAtMs = startedAtMs;
            this.delegatedMonitorOwnership = delegatedMonitorOwnership;
        }

        public String id() { return id; }
        public String ownerSession() { return request.ownerSession; }
        public String sourceKind() { return request.sourceKind; }
        public String code() { return request.code; }
        public long startedAtMs() { return startedAtMs; }
        public long endedAtMs() { return endedAtMs; }
        public T result() { return result; }
        public Throwable error() { return error; }
        public boolean isWorkerExited() { return workerExited; }
        public boolean isTerminal() { return terminalState != null; }
        public boolean isCancellationRequested() { return cancelReason.get() != null; }

        public State state() {
            State end = terminalState;
            if (end != null) return end;
            if (workerExited) return State.WORKER_EXITED;
            CancelReason reason = cancelReason.get();
            if (reason == CancelReason.TIMEOUT) return State.TIMEOUT_REQUESTED;
            if (reason != null) return State.CANCEL_REQUESTED;
            return operationActive ? State.RUNNING : State.ADMITTED;
        }

        public Completion<T> awaitCompletion() throws InterruptedException {
            terminal.await();
            return completion();
        }

        public Completion<T> awaitCompletion(long timeout, TimeUnit unit)
                throws InterruptedException {
            if (!terminal.await(timeout, unit)) return null;
            return completion();
        }

        public Completion<T> completion() {
            State end = terminalState;
            return end == null ? null
                    : new Completion<T>(end, result, error, startedAtMs, endedAtMs);
        }

        public boolean cancel() {
            return coordinator.requestCancel(this, CancelReason.USER);
        }

        Thread workerForTest() { return worker; }
    }

    private final int capacity;
    private final Semaphore admissions;
    private final Object admissionGuard = new Object();
    private final Object mutationMonitor;
    private final ThreadFactory workerFactory;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService exitObservers;
    private final LongSupplier clock;
    private final SecureRandom random;
    private final Set<Handle<?>> active = Collections.newSetFromMap(
            new ConcurrentHashMap<Handle<?>, Boolean>());
    private volatile boolean accepting = true;

    public MutationCoordinator() {
        this(DEFAULT_CAPACITY, new Object());
    }

    public MutationCoordinator(int capacity, Object mutationMonitor) {
        this(capacity,
                daemonFactory("ImageJAI-Mutation-"),
                Executors.newSingleThreadScheduledExecutor(
                        daemonFactory("ImageJAI-Mutation-Timeout-")),
                Executors.newFixedThreadPool(Math.max(1, capacity),
                        daemonFactory("ImageJAI-Mutation-Exit-")),
                System::currentTimeMillis,
                new SecureRandom(),
                mutationMonitor);
    }

    MutationCoordinator(int capacity,
                        ThreadFactory workerFactory,
                        ScheduledExecutorService scheduler,
                        ExecutorService exitObservers,
                        LongSupplier clock,
                        SecureRandom random,
                        Object mutationMonitor) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.admissions = new Semaphore(capacity, true);
        this.workerFactory = workerFactory;
        this.scheduler = scheduler;
        this.exitObservers = exitObservers;
        this.clock = clock;
        this.random = random;
        this.mutationMonitor = mutationMonitor == null ? new Object() : mutationMonitor;
    }

    public <T> Handle<T> submit(Request<T> request) {
        return submitInternal(request, null, false);
    }

    /**
     * Admit and submit a mutation. {@code onAdmitted} runs synchronously after
     * capacity is reserved but before any worker/observer thread is created.
     */
    public <T> Handle<T> submit(Request<T> request,
                                Consumer<Handle<T>> onAdmitted) {
        return submitInternal(request, onAdmitted, false);
    }

    /**
     * Delegate a worker while the calling thread retains this coordinator's
     * mutation monitor. Used only by legacy compound handlers during staged
     * migration: the parent waits for this handle before releasing the
     * monitor, so the delegated worker remains the sole mutation body.
     */
    <T> Handle<T> submitWhileMonitorHeld(Request<T> request) {
        if (!Thread.holdsLock(mutationMonitor)) {
            throw new IllegalStateException("mutation monitor is not owned by this thread");
        }
        return submitInternal(request, null, true);
    }

    boolean isMonitorHeldByCurrentThread() {
        return Thread.holdsLock(mutationMonitor);
    }

    private <T> Handle<T> submitInternal(Request<T> request,
                                         Consumer<Handle<T>> onAdmitted,
                                         boolean delegatedMonitorOwnership) {
        if (request == null) throw new IllegalArgumentException("request is required");
        final Handle<T> handle;
        synchronized (admissionGuard) {
            if (!accepting) throw new RejectedExecutionException("mutation coordinator is stopped");
            if (!admissions.tryAcquire()) {
                throw new RejectedExecutionException(
                        "mutation admission capacity reached (" + capacity + ")");
            }
            handle = new Handle<T>(this, newId(), request, clock.getAsLong(),
                    delegatedMonitorOwnership);
            active.add(handle);
        }

        try {
            if (onAdmitted != null) onAdmitted.accept(handle);
        } catch (Throwable t) {
            active.remove(handle);
            admissions.release();
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            if (t instanceof Error) throw (Error) t;
            throw new RejectedExecutionException("admission callback failed", t);
        }

        // A concurrent shutdown linearized after admission but before worker
        // creation. Complete the already-cancelled admission without creating
        // a thread that can never perform useful work.
        if (handle.cancelReason.get() != null) {
            handle.operationFinished = true;
            admissions.release();
            finishWithoutWorker(handle, State.CANCELLED);
            return handle;
        }

        final Thread worker;
        try {
            worker = workerFactory.newThread(new Runnable() {
                @Override public void run() { runWorker(handle); }
            });
            if (worker == null) throw new RejectedExecutionException("worker factory returned null");
            handle.worker = worker;
            exitObservers.execute(new Runnable() {
                @Override public void run() { observeExit(handle, worker); }
            });
            worker.start();
        } catch (Throwable t) {
            handle.error = t;
            handle.operationFinished = true;
            admissions.release();
            handle.workerFinalized.countDown();
            finishWithoutWorker(handle, State.FAILED);
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            if (t instanceof Error) throw (Error) t;
            throw new RejectedExecutionException("could not start mutation worker", t);
        }

        if (request.timeoutMs > 0L) {
            try {
                ScheduledFuture<?> timeout = scheduler.schedule(new Runnable() {
                    @Override public void run() { requestCancel(handle, CancelReason.TIMEOUT); }
                }, request.timeoutMs, TimeUnit.MILLISECONDS);
                handle.timeoutFuture = timeout;
                if (handle.operationFinished || handle.isTerminal()) timeout.cancel(false);
            } catch (RejectedExecutionException e) {
                requestCancel(handle, CancelReason.SHUTDOWN);
            }
        }
        return handle;
    }

    private <T> void runWorker(Handle<T> handle) {
        try {
            if (handle.delegatedMonitorOwnership) {
                runOwnedLifecycle(handle);
            } else {
                synchronized (mutationMonitor) {
                    runOwnedLifecycle(handle);
                }
            }
        } finally {
            ScheduledFuture<?> timeout = handle.timeoutFuture;
            if (timeout != null) timeout.cancel(false);
            admissions.release();
            handle.workerFinalized.countDown();
        }
    }

    private <T> void runOwnedLifecycle(Handle<T> handle) {
        if (handle.cancelReason.get() != null) return;
        handle.operationActive = true;
        try {
            if (handle.request.safetyEnabled) {
                handle.request.lifecycle.checkSafety();
            }
            if (handle.cancelReason.get() != null) return;
            if (handle.request.undoEnabled || handle.request.provenanceEnabled) {
                handle.request.lifecycle.beforeMutation();
            }
            synchronized (handle) {
                if (handle.cancelReason.get() != null) return;
                handle.cancellationTargetActive = true;
            }
            try {
                handle.result = handle.request.operation.run();
            } finally {
                synchronized (handle) {
                    handle.cancellationTargetActive = false;
                    handle.mutationBodyFinished = true;
                }
            }
        } catch (Throwable t) {
            handle.error = t;
        } finally {
            synchronized (handle) {
                handle.mutationBodyFinished = true;
            }
            Outcome<T> outcome = new Outcome<T>(handle.result, handle.error,
                    handle.cancelReason.get() != null);
            if (handle.request.provenanceEnabled) {
                try {
                    handle.request.lifecycle.afterMutation(outcome);
                } catch (Throwable hookFailure) {
                    if (handle.error == null) handle.error = hookFailure;
                }
            }
            synchronized (handle) {
                handle.operationFinished = true;
                handle.operationActive = false;
            }
        }
    }

    private <T> void observeExit(Handle<T> handle, Thread worker) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    handle.workerFinalized.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            handle.workerExited = true;
            CancelReason reason = handle.cancelReason.get();
            State terminalState;
            if (reason == CancelReason.TIMEOUT) {
                terminalState = State.TIMED_OUT;
            } else if (reason != null) {
                terminalState = State.CANCELLED;
            } else if (handle.error != null) {
                terminalState = State.FAILED;
            } else {
                terminalState = State.SUCCEEDED;
            }
            finish(handle, terminalState);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private <T> void finishWithoutWorker(Handle<T> handle, State state) {
        handle.workerExited = true;
        finish(handle, state);
    }

    private <T> void finish(Handle<T> handle, State state) {
        if (!handle.completionCalled.compareAndSet(false, true)) return;
        handle.endedAtMs = clock.getAsLong();
        handle.terminalState = state;
        Completion<T> completion = new Completion<T>(state, handle.result,
                handle.error, handle.startedAtMs, handle.endedAtMs);
        try {
            handle.request.lifecycle.onCompletion(completion);
        } catch (Throwable ignored) {
            // Completion telemetry must not strand a terminal handle.
        } finally {
            active.remove(handle);
            handle.terminal.countDown();
        }
    }

    private boolean requestCancel(Handle<?> handle, CancelReason reason) {
        if (handle == null) return false;
        synchronized (handle) {
            if (handle.isTerminal() || handle.operationFinished) return false;
            // A user cancel that arrives after the mutation body returned
            // must not rewrite successful work as cancelled. Timeouts and
            // shutdown still cover a stuck provenance/finalization hook.
            if (reason == CancelReason.USER && handle.mutationBodyFinished) return false;
            if (!handle.cancelReason.compareAndSet(null, reason)) return false;
            if (handle.cancellationTargetActive
                    && handle.request.cancellationAction != null
                    && handle.cancellationActionCalled.compareAndSet(false, true)) {
                try {
                    handle.request.cancellationAction.cancel();
                } catch (Throwable ignored) {
                    // Interruption below is still attempted.
                }
            }
        }
        Thread worker = handle.worker;
        if (worker != null) worker.interrupt();
        return true;
    }

    public int capacity() { return capacity; }
    public int activeCount() { return active.size(); }
    public int availableCapacity() { return admissions.availablePermits(); }
    public boolean isAccepting() { return accepting; }

    public List<Handle<?>> activeSnapshot() {
        return new ArrayList<Handle<?>>(active);
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        List<Handle<?>> snapshot;
        synchronized (admissionGuard) {
            if (!accepting) return;
            accepting = false;
            snapshot = new ArrayList<Handle<?>>(active);
        }
        for (Handle<?> handle : snapshot) {
            requestCancel(handle, CancelReason.SHUTDOWN);
        }
        scheduler.shutdownNow();
        exitObservers.shutdown();
    }

    private String newId() {
        byte[] bytes = new byte[24];
        synchronized (random) {
            random.nextBytes(bytes);
        }
        return "j_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static ThreadFactory daemonFactory(final String prefix) {
        final AtomicInteger sequence = new AtomicInteger();
        return new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + sequence.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }
}
