# 06 — Reconcile stale "Java 8" copy

## Goal

The codebase says it is "built as Java 8 bytecode" in user-facing copy, but the
build actually targets Java 11 (`pom.xml:76-78`; `AuditRow.java:15` confirms
"compiles with Java 11 bytecode"). Only the embedded terminal (pty4j/JediTerm)
is Java-version-gated. Fix the misleading copy so the dialog and project docs
match reality. Small, isolated; can be done independently of the UI stages.

## Steps

1. Update `AiRootPanel.showJavaCompatibilityDialog` (`AiRootPanel.java:1056-1069`)
   so the message explains the real situation: the plugin targets Java 11; the
   **embedded terminal** backend needs Java 11+, and on older runtimes the agent
   launches in an external terminal window. Drop the "Java 8 bytecode / Zulu 8"
   claim.
2. Update the project `CLAUDE.md` "Build" note if it repeats the Java 8 claim
   (keep it accurate: JDK 25 toolchain, Java 11 bytecode target).
3. Grep for other stale "Java 8" references in comments/copy and fix or remove.

## Files

- `src/main/java/imagejai/ui/AiRootPanel.java`
- `CLAUDE.md`
- any other files surfaced by grep.

## Exit gate

- No user-facing or doc copy claims "Java 8 bytecode".
- The compatibility dialog accurately describes the Java-11 embedded-terminal
  gate and the external-terminal fallback.
- `mvn -q compile` clean.

## Out of scope

Changing the actual bytecode target or the terminal backend gating.
