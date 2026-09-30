# ImageJAI — Third-party notice

ImageJAI is distributed under the BSD 3-Clause License. Its release JAR
contains the following third-party software and font resources. Versions below
are the versions embedded by `pom.xml`; source and license texts are available
from the linked upstream projects.

## Native-process support

- **Java Native Access (JNA) 5.13.0 and JNA Platform 4.5.2** — available
  under LGPL-2.1-or-later or Apache-2.0.
  Source: https://github.com/java-native-access/jna

## Configuration and document support

- **Gson 2.10.1** — Apache-2.0. Relocated to `imagejai.shaded.gson`.
  Source: https://github.com/google/gson
- **SnakeYAML 2.2** — Apache-2.0. Relocated to `imagejai.shaded.snakeyaml`.
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
