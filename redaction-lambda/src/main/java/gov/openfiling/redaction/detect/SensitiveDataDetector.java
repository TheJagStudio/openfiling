package gov.openfiling.redaction.detect;

import gov.openfiling.redaction.pdf.ExtractedDocument;

/** Finds PII / CUI spans in the extracted text of a document. */
public interface SensitiveDataDetector {

    DetectionResult detect(ExtractedDocument document);
}
