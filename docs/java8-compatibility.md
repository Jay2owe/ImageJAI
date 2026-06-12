# Java 8 Compatibility

ImageJAI builds plugin classes with `maven.compiler.release=8` so Fiji installs
bundled with Zulu 8 can load the `@Plugin` entry class.

The embedded terminal backend still ships in the same shaded JAR, but it is
isolated under `imagejai.engine.terminal.embedded`.

`TerminalProviderFactory` checks the runtime Java version. On Java 11+ it loads
`EmbeddedTerminalProvider` by class name; on Java 8, or if loading fails, it uses
`ExternalTerminalProvider` to launch the selected agent in an OS terminal window.

Rule: nothing outside `engine/terminal/embedded` may statically reference
pty4j/JediTerm classes.
