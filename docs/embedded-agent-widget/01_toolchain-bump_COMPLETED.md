# Stage 01 — Toolchain bump

## Why this stage exists

Current pty4j / JediTerm builds require JVM 11 bytecode; the project
still targets Java 1.8. Before any embedded-terminal work can land,
the build must target 11, a stray `javax.xml.bind` usage must move
to `java.util.Base64`, and the SciJava-managed Kotlin line must be
explicitly pinned to avoid a mismatched stdlib being pulled in.
This is a pure toolchain stage — no UI, no runtime behaviour change.

## Prerequisites

None.

## Read first

- `docs/embedded-agent-widget/00_overview.md`
- `docs/embedded-agent-widget/PLAN.md` §3 ("Build changes")
- `pom.xml` (lines 27–28, 80–81 for Java-version fields)
- `src/main/java/imagejai/ConversationLoop.java:705`
  (the `javax.xml.bind.DatatypeConverter` usage to remove)
- `CLAUDE.md` — confirms JDK 25 is the *build* JDK; the *target*
  bytecode is what changes here (to 11).

## Scope

- Bump `maven.compiler.source` / `-target` from 1.8 to 11 in `pom.xml`.
- Set `<scijava.jvm.build.version>[11,)</scijava.jvm.build.version>`.
- Pin `<kotlin.version>2.1.21</kotlin.version>` explicitly.
- Replace `javax.xml.bind.DatatypeConverter` at
  `ConversationLoop.java:705` with `java.util.Base64`.
- Verify `mvn compile` and `mvn package` pass with zero new warnings
  on JDK 25.
- Load the built JAR in the Dropbox Fiji install and confirm the
  plugin still shows up in `Plugins > AI Assistant`.

## Out of scope

- Adding `pty4j` / `jediterm` dependencies — stage 05.
- Adding the JetBrains Maven repository — stage 05 (stage 01 does not
  need it yet).
- `maven-shade-plugin` — stage 05 (only needed once pty4j native
  resources must be packaged).

## Files touched

| Path                                                     | Action | Reason                                          |
|----------------------------------------------------------|--------|-------------------------------------------------|
| `pom.xml`                                                | MODIFY | JVM 11, Kotlin pin, SciJava build-version floor |
| `src/main/java/imagejai/ConversationLoop.java`| MODIFY | Replace `DatatypeConverter` with `Base64`       |

## Implementation sketch

`pom.xml` additions / changes:

```xml
<properties>
  <maven.compiler.source>11</maven.compiler.source>
  <maven.compiler.target>11</maven.compiler.target>
  <scijava.jvm.build.version>[11,)</scijava.jvm.build.version>
  <kotlin.version>2.1.21</kotlin.version>
</properties>
```

`ConversationLoop.java:705` — search the file for
`DatatypeConverter` and swap the pattern. Typical before/after:

```java
// before
String b64 = javax.xml.bind.DatatypeConverter.printBase64Binary(bytes);

// after
String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
```

Check for a matching `parseBase64Binary` → `Base64.getDecoder().decode(...)`
pair anywhere nearby.

## Exit gate

1. `mvn -q compile` succeeds on JDK 25 with target 11 bytecode. No
   new compiler warnings.
2. `mvn -q package` succeeds; the shaded JAR is produced at the
   expected path.
3. Built JAR is copied into the Dropbox Fiji plugins folder; Fiji
   launches; `Plugins > AI Assistant` still opens the existing
   chat window.
4. Existing agents in `AgentLauncher.KNOWN_AGENTS` still launch
   externally via the play button — this stage did not touch
   `AgentLauncher.java` and must not regress it.
5. `grep -R "javax.xml.bind" src/` returns no results.

## Known risks

- `pom-scijava:37.0.0` may warn about the Java-version override.
  Expected and documented — SciJava's build-version floor is meant
  to be bumped per-project. If a hard error appears, note the
  message verbatim in the commit so stage 05 can reference it.
- Other callers of `DatatypeConverter` may exist beyond line 705 —
  grep the whole `src/` tree; fix every occurrence in this stage.
