package imagejai.config;

import org.junit.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReleaseMetadataTest {
    private static final String RELEASE_VERSION = "0.3.0";

    @Test
    public void canonicalVersionMatchesPomCitationAndDocumentation() throws Exception {
        assertEquals(RELEASE_VERSION, Constants.VERSION);
        assertEquals(RELEASE_VERSION, projectVersion(root().resolve("pom.xml")));

        String citation = read("CITATION.cff");
        Matcher cffVersion = Pattern.compile("(?m)^version:\\s*[\"']?([^\"'\\s]+)")
                .matcher(citation);
        assertTrue("CITATION.cff has no version", cffVersion.find());
        assertEquals(RELEASE_VERSION, cffVersion.group(1));

        assertTrue(read("README.md").contains("imagej-ai-" + RELEASE_VERSION + ".jar"));
        assertTrue(read("docs/DEVELOPER.md")
                .contains("imagej-ai-" + RELEASE_VERSION + ".jar"));
        assertTrue(read("docs/USER_GUIDE.md")
                .contains("imagej-ai-" + RELEASE_VERSION + ".jar"));
    }

    @Test
    public void pomDefinesDeterministicArchiveAndExcludesMavenMetadata()
            throws Exception {
        String pom = read("pom.xml");
        assertTrue(pom.contains("<project.build.outputTimestamp>"
                + "2026-07-15T00:00:00Z</project.build.outputTimestamp>"));
        assertTrue(pom.contains("<outputTimestamp>${project.build.outputTimestamp}"
                + "</outputTimestamp>"));
        assertTrue(pom.contains("<Implementation-Date>${project.build.outputTimestamp}"
                + "</Implementation-Date>"));
        assertTrue(pom.contains("<exclude>META-INF/maven/**</exclude>"));
        assertFalse("wall-clock manifest dates break reproducibility",
                pom.contains("<Implementation-Date>${maven.build.timestamp}"));
    }

    @Test
    public void buildScriptHasExplicitSafeBuildAndInstallContracts() throws Exception {
        String script = read("build.sh");
        assertTrue(script.contains("--skip-tests"));
        assertTrue(script.contains("--no-deploy"));
        assertTrue(script.contains("maven_args=(clean package -Denforcer.skip=true)"));
        assertTrue(script.contains("jar_file=\"target/$jar_name\""));
        assertTrue(script.contains("install_verified_artifact"));
        assertFalse(script.contains("maven.test.skip"));
        assertFalse(script.contains("2>/dev/null"));
        assertFalse(script.contains("head -1"));

        int stagedHash = script.indexOf("staged_hash=\"$(sha256_file");
        int replacement = script.indexOf("mv -f \"$staged\"");
        int staleRemoval = script.indexOf("find \"$plugins_dir\"");
        assertTrue("staged copy must be verified before replacement",
                stagedHash >= 0 && stagedHash < replacement);
        assertTrue("stale jars must be removed only after replacement",
                replacement < staleRemoval);
    }

    @Test
    public void noticeNamesEveryEmbeddedRuntimeDependencyFamily() throws Exception {
        String notice = read("src/main/resources/META-INF/NOTICE.md");
        String[] required = {"JediTerm", "pty4j", "Java Native Access",
                "Kotlin standard library", "JetBrains Java annotations",
                "SLF4J API", "SnakeYAML", "Apache PDFBox",
                "PDFBox IO", "FontBox", "Apache Commons Logging",
                "JetBrains Mono", "Noto Emoji"};
        for (String name : required) {
            assertTrue("NOTICE missing " + name, notice.contains(name));
        }
    }

    private static String projectVersion(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Object document = factory.newDocumentBuilder().parse(pom.toFile());
        String expression = "/*[local-name()='project']/*[local-name()='version']/text()";
        return ((String) XPathFactory.newInstance().newXPath().evaluate(
                expression, document, XPathConstants.STRING)).trim();
    }

    private static Path root() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        if (!Files.isRegularFile(current.resolve("pom.xml"))) {
            throw new IllegalStateException("Tests must run from the project root: " + current);
        }
        return current;
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(root().resolve(relative)),
                StandardCharsets.UTF_8);
    }
}
