# Build & Deploy Configuration Review - ImageJAI

## Severity Summary
- **BUG**: 2
- **ISSUE**: 3
- **INEFFICIENCY**: 2
- **NIT**: 1

## Findings

### BUG

**[BUG]** `src/main/java/imagejai/ImageJAIPlugin.java:260` — Hardcoded user-specific Dropbox path
- The `findAgentWorkspace()` method contains a hardcoded absolute path `<local ImageJAI agent workspace>` that includes the username and machine-specific directory structure. This will only work on the developer's machine and will fail silently for all other users, falling back to an empty `.imagej-ai/agent/` workspace.
- Replace the hardcoded Dropbox path with a repository-relative fallback: check alongside the plugin JAR location or in a standard location like `~/ImageJAI/agent`. Remove the user-specific path entirely.

**[BUG]** `pom.xml:145` — maven-jar-plugin missing version pin
- The `maven-jar-plugin` has no explicit `<version>` tag and relies on the parent POM (scijava 37.0.0) to inherit a version. If pom-scijava changes its version of maven-jar-plugin, the build behavior could change unexpectedly. This violates the principle of reproducible builds.
- Add `<version>3.3.0</version>` (or the currently inherited version) explicitly to the `<plugin>` declaration in the `<plugins>` section.

### ISSUE

**[ISSUE]** `pom.xml:136` — maven-compiler-plugin missing version pin
- The `maven-compiler-plugin` declared in the `<plugins>` section has no `<version>` tag. While the parent POM provides one, explicit versioning ensures build reproducibility and makes the effective version visible in the pom.xml itself.
- Add an explicit version pin (e.g., `<version>3.11.0</version>`) to the maven-compiler-plugin declaration.

**[ISSUE]** `build.sh:9` — Tests skipped by default in build script
- The `build.sh` script unconditionally passes `-DskipTests`, meaning unit tests are never run during the build. This defeats the purpose of having 30+ test classes and increases the risk of regressions, particularly around the recent Java 8 compatibility work in commit 79cfe57.
- Either make `-DskipTests` optional (test by default, skip only on explicit flag), or document why tests must be skipped (e.g., Fiji not available in build environment). Consider adding a separate test target.

**[ISSUE]** `docs/java8-compatibility.md:13–14` — Java 8 compatibility rule not enforced
- The documentation states: "Rule: nothing outside `engine/terminal/embedded` may statically reference pty4j/JediTerm classes." However, there is no build-time check (e.g., forbidden-apis Maven plugin) to enforce this rule. If a future developer violates this rule, the plugin will fail to load on Java 8 with a NoClassDefFoundError, only detected at runtime.
- Add a forbidden-apis Maven plugin configuration to statically prevent any imports of `org.jetbrains.pty4j.*` or `com.jediterm.*` outside the `embedded` package at compile time.

### INEFFICIENCY

**[INEFFICIENCY]** `pom.xml:1–177` — No CI/CD pipeline configured
- There is no `.github/workflows/` or equivalent CI/CD setup to verify builds on multiple Java versions (8, 11, 21, 25) or to automatically test the Java 8 compatibility constraint. The project ships with commit 79cfe57 ("Keep plugin loadable on Java 8") but has no way to ensure future commits maintain that property.
- Add a GitHub Actions workflow (or similar CI tool) that: builds on JDK 8, 11, and 25; runs `mvn clean test`; optionally smoke-tests the JAR against a Java 8 classloader to verify no Java 9+ bytecode intrinsics are used.

**[INEFFICIENCY]** `build.sh:6` — Deploy path assumes Fiji.app is a sibling directory
- The build script assumes Fiji.app exists at `../../Fiji.app` relative to the project root. This is fragile and non-portable. Users with a different directory structure or on CI will see the warning "Fiji plugins directory not found" and must copy the JAR manually.
- Add an environment variable (e.g., `FIJI_HOME`) that can be set to override the default path, or prompt the user interactively if the directory is not found.

### NIT

**[NIT]** `pom.xml:32` — Vague scijava.jvm.build.version property
- The property `scijava.jvm.build.version=[8,)` is a range that allows any JDK version 8 or higher. While correct, this is somewhat vague and doesn't prevent the build on extremely new JDKs where new language features might be accidentally introduced. The compiler plugins already enforce Java 8 bytecode, so this property is somewhat redundant.
- This is acceptable as-is, but consider documenting that the enforcer.skip=true override is intentional (to allow building on JDK 25).

---

## Notes on Java 8 Compatibility

### Commit 79cfe57 Analysis
The commit successfully refactors the terminal components to be lazy-loaded only on Java 11+, allowing the main plugin classes to compile to Java 8 bytecode. The lazy-loading via `TerminalProviderFactory` correctly:
- Checks runtime Java version with `System.getProperty("java.version")`
- Loads `EmbeddedTerminalProvider` by class name on Java 11+
- Falls back to `ExternalTerminalProvider` on Java 8

No Java 9+ APIs detected in src/main/java (List.of, Map.of, String.repeat, String.strip, Stream.toList, var keyword, switch expressions, etc.).

### Missing Test Coverage
With tests skipped by default (build.sh:9), there is no automated check that:
1. The plugin actually loads on Java 8 (classloading test)
2. pty4j/JediTerm are not statically imported outside embedded/ (static analysis)
3. The lazy-loading factory works correctly on both Java 8 and Java 11+ (integration test)

---

## Recommendations (Priority Order)

1. **Remove the hardcoded Dropbox path** (BUG, high impact) — breaks the build for all users except the developer.
2. **Add a forbidden-apis Maven plugin** (ISSUE, prevents future regressions) — ensures Java 8 compatibility is maintained over time.
3. **Configure CI/CD for multi-JDK testing** (INEFFICIENCY, visibility) — verifies Java 8 compatibility automatically on every commit.
4. **Pin all plugin versions explicitly** (ISSUE + NIT, best practice) — improves build reproducibility.
5. **Re-enable tests or document why they are skipped** (ISSUE, maintenance) — prevents silent regressions.
6. **Make the Fiji deploy path configurable** (INEFFICIENCY, portability) — allows builds on any machine or in CI.
