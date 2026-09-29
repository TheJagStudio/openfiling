package gov.openfiling.redaction.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gov.openfiling.redaction.pdf.ExtractedDocument;
import gov.openfiling.redaction.pdf.PageText;

/**
 * Deterministic PII / CUI rules. Always runs (it is the safety net under Claude). Each rule is a regular expression
 * whose group {@code "v"} (or the whole match) is the sensitive span, optionally followed by a validator
 * (Luhn for card numbers, SSN area/group/serial sanity, ABA checksum for routing numbers, octet ranges for IPv4).
 * Labelled rules (dates of birth, account / routing / passport / licence / EIN numbers) only fire next to their label,
 * which keeps false positives in technical patent text low.
 */
public class RuleBasedDetector implements SensitiveDataDetector {

    private record Rule(Category category, String reason, Pattern pattern, Predicate<String> validator) {
    }

    private static final String MONTHS = "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?"
            + "|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)";
    private static final String DATE = "(?:\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|" + MONTHS
            + "\\.?\\s+\\d{1,2},?\\s+\\d{4}|\\d{1,2}\\s+" + MONTHS + "\\.?,?\\s+\\d{4})";
    private static final String NUMBER_LABEL = "(?:\\s*(?:number|num\\.?|no\\.?|#))?\\s*[:#.\\-]?\\s*";
    private static final String STREET_SUFFIX = "(?:Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr|Court|Ct"
            + "|Way|Place|Pl|Terrace|Ter|Circle|Cir|Parkway|Pkwy|Highway|Hwy|Square|Sq|Trail|Trl)";

    private static final List<Rule> RULES = List.of(
            // --- government identifiers
            new Rule(Category.SSN, "US Social Security number pattern (valid area/group/serial)",
                    Pattern.compile("(?<![\\d-])(?<v>\\d{3}[- ]\\d{2}[- ]\\d{4})(?![\\d-])"),
                    RuleBasedDetector::validSsn),
            new Rule(Category.SSN, "Labelled Social Security number",
                    Pattern.compile("(?i)\\b(?:SSN|SSAN|social\\s+security(?:\\s+(?:number|no\\.?|#))?)\\s*[:#]?\\s*(?<v>\\d{9})\\b"),
                    RuleBasedDetector::validSsn),
            new Rule(Category.TAX_ID, "ITIN pattern",
                    Pattern.compile("(?<![\\d-])(?<v>9\\d{2}-(?:5\\d|6[0-5]|7\\d|8[0-8]|9[0-24-9])-\\d{4})(?![\\d-])"), null),
            new Rule(Category.TAX_ID, "Labelled taxpayer / employer identification number",
                    Pattern.compile("(?i)\\b(?:ITIN|EIN|FEIN|TIN|tax(?:payer)?\\s+(?:identification|ID)|employer\\s+identification)"
                            + NUMBER_LABEL + "(?<v>\\d{2}-\\d{7}|\\d{3}-\\d{2}-\\d{4}|\\d{9})\\b"), null),
            new Rule(Category.GOVERNMENT_ID, "Labelled passport number",
                    Pattern.compile("(?i)\\bpassport" + NUMBER_LABEL + "(?<v>[A-Z0-9]{6,9})\\b"),
                    RuleBasedDetector::containsDigit),
            new Rule(Category.GOVERNMENT_ID, "Labelled driver's licence number",
                    Pattern.compile("(?i)\\b(?:driver'?s?\\s+licen[cs]e|DLN)" + NUMBER_LABEL
                            + "(?:\\([A-Z]{2}\\)\\s*)?(?<v>[A-Z0-9][A-Z0-9-]{4,15}[A-Z0-9])\\b"),
                    RuleBasedDetector::containsDigit),
            // --- contact data
            new Rule(Category.EMAIL, "E-mail address",
                    Pattern.compile("(?<v>[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24})"), null),
            new Rule(Category.PHONE, "US phone number",
                    Pattern.compile("(?<![\\w/.-])(?<v>(?:\\+?1[\\s.-]?)?(?:\\(\\d{3}\\)\\s?|\\d{3}[\\s.-])\\d{3}[\\s.-]\\d{4})"
                            + "(?:\\s*(?:x|ext\\.?)\\s*\\d{1,5})?(?![\\w-])"), null),
            new Rule(Category.PHONE, "International phone number",
                    Pattern.compile("(?<![\\w+])(?<v>\\+(?:[2-9]\\d{0,2})[\\s.-]\\(?\\d{1,4}\\)?(?:[\\s.-]\\d{2,4}){2,4})(?![\\w-])"), null),
            new Rule(Category.ADDRESS, "Street address",
                    Pattern.compile("(?<![\\w-])(?<v>\\d{1,6}(?:-\\d{1,4})?\\s+(?:[NSEW]\\.?\\s+)?(?:[A-Z][A-Za-z'-]+\\.?\\s+){1,4}"
                            + STREET_SUFFIX + "\\b\\.?(?:,?\\s+(?:Apt|Apartment|Suite|Ste|Unit|#)\\.?\\s*[A-Za-z0-9-]+)?"
                            + "(?:,\\s*[A-Z][A-Za-z .'-]{1,40},\\s*[A-Z]{2}\\s+\\d{5}(?:-\\d{4})?)?)"), null),
            new Rule(Category.ADDRESS, "Post office box",
                    Pattern.compile("(?i)(?<v>\\bP\\.?\\s?O\\.?\\s+Box\\s+\\d{1,7})\\b"), null),
            new Rule(Category.IP_ADDRESS, "IPv4 address",
                    Pattern.compile("(?<![\\d.])(?<v>(?:\\d{1,3}\\.){3}\\d{1,3})(?![\\d.])"),
                    RuleBasedDetector::validIpv4),
            // --- dates of birth (labelled)
            new Rule(Category.DATE_OF_BIRTH, "Labelled date of birth",
                    Pattern.compile("(?i)\\b(?:DOB|D\\.O\\.B\\.?|date\\s+of\\s+birth|birth\\s*date|born(?:\\s+on)?)\\s*[:\\-]?\\s*(?<v>"
                            + DATE + ")"), null),
            // --- financial
            new Rule(Category.FINANCIAL_ACCOUNT, "Payment card number (Luhn valid)",
                    Pattern.compile("(?<![\\d-])(?<v>\\d(?:[ -]?\\d){12,18})(?![\\d-])"),
                    RuleBasedDetector::validCard),
            new Rule(Category.FINANCIAL_ACCOUNT, "Labelled bank routing number",
                    Pattern.compile("(?i)\\b(?:routing|ABA|RTN|transit)" + NUMBER_LABEL + "(?<v>\\d{9})\\b"), null),
            new Rule(Category.FINANCIAL_ACCOUNT, "Labelled bank account number",
                    Pattern.compile("(?i)\\b(?:(?:bank\\s+|checking\\s+|savings\\s+)?account|acct\\.?|a/c)" + NUMBER_LABEL
                            + "(?<v>[X*\\d][X*\\d -]{2,22}\\d)\\b"), null),
            new Rule(Category.FINANCIAL_ACCOUNT, "IBAN",
                    Pattern.compile("\\b(?<v>[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?)\\b"),
                    RuleBasedDetector::validIban),
            // --- CUI / export control / legacy markings
            new Rule(Category.CUI_MARKING, "CUI banner or portion marking",
                    Pattern.compile("(?<![A-Za-z])(?<v>\\(?CUI(?://[A-Z][A-Z0-9-]*(?:/[A-Z][A-Z0-9-]*)*)*\\)?)(?![A-Za-z])"), null),
            new Rule(Category.CUI_MARKING, "CUI legend",
                    Pattern.compile("(?i)(?<v>\\bcontrolled\\s+unclassified\\s+information\\b)"), null),
            new Rule(Category.CUI_MARKING, "FOUO marking",
                    Pattern.compile("(?<v>\\bFOUO\\b|(?i:\\bfor\\s+official\\s+use\\s+only\\b))"), null),
            new Rule(Category.CUI_MARKING, "DoD distribution statement",
                    Pattern.compile("(?i)(?<v>\\bdistribution\\s+statement\\s+[B-F]\\b[^\\n.]*\\.?)"), null),
            new Rule(Category.EXPORT_CONTROLLED, "Export-control marking",
                    Pattern.compile("(?<v>\\bITAR\\b|\\bEAR99\\b|\\bECCN\\s*:?\\s*\\d[A-E]\\d{3}(?:\\.[a-z0-9.]+)?\\b"
                            + "|(?i:\\bexport[- ]controlled\\b)|(?i:\\binternational\\s+traffic\\s+in\\s+arms\\s+regulations\\b)"
                            + "|(?i:\\bexport\\s+administration\\s+regulations\\b)|(?i:\\barms\\s+export\\s+control\\s+act\\b))"), null)
    );

    @Override
    public DetectionResult detect(ExtractedDocument document) {
        List<Finding> findings = new ArrayList<>();
        for (PageText page : document.pages()) {
            findings.addAll(detect(page.number(), page.text()));
        }
        return DetectionResult.of(findings);
    }

    /** Findings for one page of text. */
    public List<Finding> detect(int page, String text) {
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                int start = m.start("v");
                int end = m.end("v");
                if (end <= start) {
                    continue;
                }
                String value = text.substring(start, end);
                if (rule.validator() == null || rule.validator().test(value)) {
                    findings.add(new Finding(page, start, end, rule.category(), Source.RULES, rule.reason()));
                }
            }
        }
        return findings;
    }

    /** Masks every rule match in a free-text string (used to scrub model-written reasons). */
    public String mask(String text) {
        List<Finding> found = FindingMerger.merge(detect(1, text));
        if (found.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = found.size() - 1; i >= 0; i--) {
            sb.replace(found.get(i).start(), found.get(i).end(), "[REDACTED]");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- validators

    static boolean validSsn(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() != 9) {
            return false;
        }
        int area = Integer.parseInt(digits.substring(0, 3));
        int group = Integer.parseInt(digits.substring(3, 5));
        int serial = Integer.parseInt(digits.substring(5));
        return area != 0 && area != 666 && area < 900 && group != 0 && serial != 0;
    }

    static boolean validCard(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        boolean knownPrefix = digits.startsWith("4") || digits.matches("^(5[1-5]|2[2-7]|3[47]|6011|65|35|30[0-5]|36|38).*");
        return knownPrefix && luhn(digits);
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    static boolean validIpv4(String value) {
        for (String octet : value.split("\\.")) {
            if (octet.length() > 1 && octet.startsWith("0") || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    static boolean validIban(String value) {
        String iban = value.replace(" ", "");
        if (iban.length() < 15 || iban.length() > 34) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int mod = 0;
        for (char c : rearranged.toCharArray()) {
            int v = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            mod = v > 9 ? (mod * 100 + v) % 97 : (mod * 10 + v) % 97;
        }
        return mod == 1;
    }

    static boolean containsDigit(String value) {
        return value.chars().anyMatch(Character::isDigit);
    }
}
