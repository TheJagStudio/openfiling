package gov.openfiling.api.repository.spec;

import java.util.Locale;

/** Shared helpers for Criteria filter builders. */
public final class SpecSupport {

    public static final char ESCAPE = '\\';

    private SpecSupport() {
    }

    /** {@code %text%}, lower-cased, with LIKE wildcards in user input escaped. */
    public static String containsPattern(String text) {
        return "%" + escape(text.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    public static String prefixPattern(String text) {
        return escape(text) + "%";
    }

    public static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** Digits of an application number query ({@code "19/000,123"} &rarr; {@code "19000123"}). */
    public static String digitsOnly(String text) {
        return text.replaceAll("[^0-9]", "");
    }
}
