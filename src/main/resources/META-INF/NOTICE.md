# ImageJAI — Third-party NOTICE

This plugin bundles the following third-party components for the
embedded terminal renderer and PTY backend.

## JediTerm (LGPL-3.0)

- Source: https://github.com/JetBrains/jediterm
- Upstream artifacts: `org.jetbrains.jediterm:jediterm-core`,
  `jediterm-ui`, published to the JetBrains
  `intellij-dependencies` Maven repo.
- LGPL-3.0: users may replace the bundled JediTerm with a modified
  version by dropping it into the Fiji plugins folder. This plugin
  links against JediTerm dynamically via the standard Java
  classloader, satisfying the LGPL linking clause.

## pty4j (EPL-1.0)

- Source: https://github.com/JetBrains/pty4j
- Upstream artifact: `org.jetbrains.pty4j:pty4j`, published to
  Maven Central.
- EPL-1.0: source available at the upstream repository above.

## JetBrains Mono (Apache-2.0)

- Source: https://github.com/JetBrains/JetBrainsMono
- Bundled as `src/main/resources/fonts/JetBrainsMono-Regular.ttf`.

## Noto Emoji monochrome (SIL OFL 1.1)

- Source: https://github.com/googlefonts/noto-emoji
- Bundled as `src/main/resources/fonts/NotoEmoji-Regular.ttf`.

## Kotlin stdlib (Apache-2.0) — TRANSITIVE DEP

- Source: https://github.com/JetBrains/kotlin
- Pulled in as a transitive dependency of pty4j / JediTerm. Pinned
  via `pom.xml` `<kotlin.version>` in stage 05.
