# ImageJAI — Third-party notice

ImageJAI is distributed under the BSD 3-Clause License. Its release JAR
contains the following third-party software and font resources. Versions below
are the versions embedded by `pom.xml`; source and license texts are available
from the linked upstream projects.

## Embedded terminal and native-process support

- **JediTerm core and UI 3.66** — LGPL-3.0.
  Source: https://github.com/JetBrains/jediterm
- **pty4j 0.13.12** — EPL-1.0.
  Source: https://github.com/JetBrains/pty4j
- **Java Native Access (JNA and JNA Platform) 5.13.0** — available under
  LGPL-2.1-or-later or Apache-2.0.
  Source: https://github.com/java-native-access/jna
- **Kotlin standard library 2.1.21** — Apache-2.0.
  Source: https://github.com/JetBrains/kotlin
- **JetBrains Java annotations 24.0.1** — Apache-2.0.
  Source: https://github.com/JetBrains/java-annotations
- **SLF4J API 1.7.36** — MIT.
  Source: https://github.com/qos-ch/slf4j

## Configuration and document support

- **SnakeYAML 2.2** — Apache-2.0.
  Source: https://bitbucket.org/snakeyaml/snakeyaml
- **Apache PDFBox, PDFBox IO, and FontBox 3.0.2** — Apache-2.0.
  Source: https://github.com/apache/pdfbox
- **Apache Commons Logging 1.2** — Apache-2.0.
  Source: https://github.com/apache/commons-logging

The shaded JAR also retains Apache PDFBox's upstream `META-INF/NOTICE`, which
attributes the data and code incorporated by PDFBox, including Adobe glyph
lists, Unicode data, TwelveMonkeys ImageIO portions, and the bundled ICC
profile.

## Font resources

- **JetBrains Mono Regular** — Apache-2.0.
  Source: https://github.com/JetBrains/JetBrainsMono
- **Noto Emoji Regular (monochrome)** — SIL Open Font License 1.1.
  Source: https://github.com/googlefonts/noto-emoji
