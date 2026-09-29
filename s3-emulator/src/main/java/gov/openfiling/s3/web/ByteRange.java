package gov.openfiling.s3.web;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gov.openfiling.s3.S3Exception;

/**
 * A single HTTP byte range resolved against an object size ({@code first..last} inclusive).
 * Like S3: a syntactically invalid or multi-range header is ignored (full object), an unsatisfiable one is 416.
 */
public record ByteRange(long first, long last) {

    private static final Pattern SINGLE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    public long length() {
        return last - first + 1;
    }

    public static Optional<ByteRange> parse(String header, long size) {
        if (header == null) {
            return Optional.empty();
        }
        Matcher m = SINGLE.matcher(header.trim());
        if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
            return Optional.empty();
        }
        try {
            if (m.group(1).isEmpty()) {
                long suffix = Long.parseLong(m.group(2));
                if (suffix == 0 || size == 0) {
                    throw unsatisfiable();
                }
                return Optional.of(new ByteRange(Math.max(0, size - suffix), size - 1));
            }
            long first = Long.parseLong(m.group(1));
            long last = m.group(2).isEmpty() ? size - 1 : Math.min(Long.parseLong(m.group(2)), size - 1);
            if (!m.group(2).isEmpty() && Long.parseLong(m.group(2)) < first) {
                return Optional.empty();
            }
            if (first >= size) {
                throw unsatisfiable();
            }
            return Optional.of(new ByteRange(first, last));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static S3Exception unsatisfiable() {
        return new S3Exception(416, "InvalidRange", "The requested range is not satisfiable");
    }
}
