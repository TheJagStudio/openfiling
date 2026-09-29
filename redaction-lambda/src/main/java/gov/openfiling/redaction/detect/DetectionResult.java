package gov.openfiling.redaction.detect;

import java.util.List;

/**
 * Output of a detector.
 *
 * @param findings       detected spans
 * @param notes          human-readable notes for the report (never document text)
 * @param degraded       true when the detector could not analyse part of the document (e.g. a refusal or an API
 *                       error), so that part is covered by the rules only
 */
public record DetectionResult(List<Finding> findings, List<String> notes, boolean degraded) {

    public DetectionResult {
        findings = List.copyOf(findings);
        notes = List.copyOf(notes);
    }

    public static DetectionResult of(List<Finding> findings) {
        return new DetectionResult(findings, List.of(), false);
    }
}
