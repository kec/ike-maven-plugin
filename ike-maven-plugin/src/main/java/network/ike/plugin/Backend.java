package network.ike.plugin;

/**
 * AsciiDoc output backends supported by the {@link AsciidocMojo}.
 *
 * <p>Each backend maps to an AsciidoctorJ backend name and a default
 * output subdirectory under the goal's {@code outputDirectory}.
 */
public enum Backend {

    /** HTML5 — website and CSS-to-PDF renderer input. */
    HTML("html5", "html"),

    /** Prawn PDF — direct AsciiDoc-to-PDF via JRuby. */
    PDF("pdf", "pdf-prawn"),

    /** DocBook 5 — intermediate XML for XEP/FOP XSL-FO pipelines. */
    DOCBOOK("docbook5", "docbook");

    private final String asciidoctorName;
    private final String outputSubdir;

    Backend(String asciidoctorName, String outputSubdir) {
        this.asciidoctorName = asciidoctorName;
        this.outputSubdir = outputSubdir;
    }

    /** The backend name as AsciidoctorJ expects it. */
    public String asciidoctorName() {
        return asciidoctorName;
    }

    /** Default output subdirectory under the goal's output root. */
    public String outputSubdir() {
        return outputSubdir;
    }

    /**
     * Whether this backend supports AsciidoctorJ Postprocessor extensions.
     * The Prawn PDF backend crashes when a Java Postprocessor is registered
     * (JRuby type conversion error in PostprocessorProxy).
     */
    public boolean supportsPostprocessor() {
        return this != PDF;
    }

    /**
     * Parse a backend name (case-insensitive).
     *
     * @param name one of: html, pdf, prawn, docbook
     * @return the matching backend
     * @throws IllegalArgumentException if name is not recognized
     */
    public static Backend parse(String name) {
        return switch (name.strip().toLowerCase()) {
            case "html", "html5" -> HTML;
            case "pdf", "prawn" -> PDF;
            case "docbook", "docbook5" -> DOCBOOK;
            default -> throw new IllegalArgumentException(
                    "Unknown backend: " + name
                            + ". Valid values: html, pdf, prawn, docbook");
        };
    }
}
