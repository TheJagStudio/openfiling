package gov.openfiling.api.service;

import gov.openfiling.api.model.ApplicationType;

import java.time.LocalDate;
import java.util.Optional;

/** Application / publication / patent number formats. */
public final class ApplicationNumbers {

    private ApplicationNumbers() {
    }

    /** Series + zero-padded 6-digit serial, e.g. {@code 19000123}. */
    public static String applicationNumber(ApplicationType type, long serial) {
        return type.getSeries() + String.format("%06d", serial);
    }

    /** {@code 19000123} &rarr; {@code 19/000,123}; {@code null} stays {@code null}. */
    public static String display(String number) {
        if (number == null || number.length() != 8) {
            return number;
        }
        return number.substring(0, 2) + "/" + number.substring(2, 5) + "," + number.substring(5);
    }

    /** Accepts {@code 19000123} or {@code 19/000,123}; empty unless exactly 8 digits remain. */
    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String digits = input.replaceAll("[\\s/,]", "");
        return digits.matches("\\d{8}") ? Optional.of(digits) : Optional.empty();
    }

    /** Pre-grant publication number, e.g. {@code US20260012345A1}. */
    public static String publicationNumber(LocalDate date, long serial) {
        return "US" + date.getYear() + String.format("%07d", serial) + "A1";
    }

    /** Patent number: B2 when a pre-grant publication exists, B1 otherwise. */
    public static String patentNumber(long serial, boolean previouslyPublished) {
        return "US" + String.format("%08d", serial) + (previouslyPublished ? "B2" : "B1");
    }
}
