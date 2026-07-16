# ImageJAI Developer Notes

## Build

Requirements:

- Maven 3.6 or newer.
- JDK 11 or newer; local release builds use JDK 25. The whole plugin is
  compiled as Java 11 bytecode (see `pom.xml`) and therefore requires a
  Fiji/ImageJ runtime on Java 11 or newer.

Commands:

```bash
mvn clean test -Denforcer.skip=true
mvn clean package -DskipTests -Denforcer.skip=true
```

The uploadable plugin jar is `target/imagej-ai-0.3.0.jar`. Do not upload
`*-sources.jar`, `*-tests.jar`, or `original-*.jar`.

## Runtime Dependencies

Fiji provides ImageJ and SciJava dependencies at runtime. The plugin jar shades
the embedded terminal and provider-side Java dependencies it needs. The jar
build excludes Maven metadata from `META-INF/maven/**` so the Fiji updater does
not infer development-time Maven dependencies from the packaged jar.

## Public Repository Surface

Keep these in the public repository:

- `src/main/java/`
- `src/main/resources/`
- `src/test/java/`
- `agent/providers/` files bundled by `pom.xml`
- user-facing docs
- build and CI files

Do not publish local experiment outputs, private lab workflow transcripts,
credential files, generated caches, or machine-specific paths. In particular,
keep these out of public commits:

- `agent/work_in_progress/`
- `agent/.tmp/`
- `graphify-out/`
- `target/`
- `.claude/`, `.codex*/`, and other local agent state
- `.env`, `.env.*`, and `agent/providers/.secrets/`

## Release Checklist

1. Confirm `pom.xml`, `README.md`, and `Constants.VERSION` agree.
2. Run `mvn clean test -Denforcer.skip=true`.
3. Run `mvn clean package -DskipTests -Denforcer.skip=true`.
4. Inspect `target/imagej-ai-0.3.0.jar` for `plugins.config` and plugin classes.
5. Confirm the jar does not contain `META-INF/maven/**`.
6. Run `mvn dependency:tree -Dscope=runtime -Denforcer.skip=true` and confirm
   no private project dependency is listed.
7. Scan the public tree for private paths and credentials before tagging.

## Update Site

No ImageJ update-site upload workflow is enabled yet. Add a manual
`workflow_dispatch` upload only after the update-site name, URL, upload user,
and `IMAGEJ_UPLOAD_PASSWORD` GitHub secret are known. Keep dry-run enabled by
default.
