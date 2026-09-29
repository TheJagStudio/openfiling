package gov.openfiling.redaction.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class RuleBasedDetectorTest {

    private final RuleBasedDetector detector = new RuleBasedDetector();

    /** Returns the matched values (tests only — production code never materialises them). */
    private List<String> values(String text, Category category) {
        return detector.detect(1, text).stream()
                .filter(f -> f.category() == category)
                .map(f -> text.substring(f.start(), f.end()))
                .toList();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Inventor SSN: 078-05-1120 on file|SSN|078-05-1120",
            "social security number 078051120.|SSN|078051120",
            "SSN 219 09 9999|SSN|219 09 9999",
            "ITIN 912-70-1234 issued|TAX_ID|912-70-1234",
            "EIN: 12-3456789|TAX_ID|12-3456789",
            "Taxpayer Identification Number 123456789|TAX_ID|123456789",
            "Passport No. X12345678 (US)|GOVERNMENT_ID|X12345678",
            "Driver's License # D1234-5678-9012|GOVERNMENT_ID|D1234-5678-9012",
            "mail jane.doe+patents@example-mail.com now|EMAIL|jane.doe+patents@example-mail.com",
            "Phone: (555) 010-4477|PHONE|(555) 010-4477",
            "cell 555.010.9876 ext 12|PHONE|555.010.9876",
            "call +1 555-010-2233 today|PHONE|+1 555-010-2233",
            "Tel. +44 20 7946 0958|PHONE|+44 20 7946 0958",
            "lives at 1234 Maple Grove Lane, Apt 5B, Springfield, VA 22150 since|ADDRESS|1234 Maple Grove Lane, Apt 5B, Springfield, VA 22150",
            "Mail to P.O. Box 4521|ADDRESS|P.O. Box 4521",
            "server 192.168.10.25 logged|IP_ADDRESS|192.168.10.25",
            "Date of Birth: 03/14/1985|DATE_OF_BIRTH|03/14/1985",
            "DOB 1985-03-14|DATE_OF_BIRTH|1985-03-14",
            "born on March 14, 1985 in Ohio|DATE_OF_BIRTH|March 14, 1985",
            "card 4111 1111 1111 1111 exp|FINANCIAL_ACCOUNT|4111 1111 1111 1111",
            "ABA Routing Number: 021000021|FINANCIAL_ACCOUNT|021000021",
            "Checking Account No. 4455667788|FINANCIAL_ACCOUNT|4455667788",
            "IBAN GB82 WEST 1234 5698 7654 32|FINANCIAL_ACCOUNT|GB82 WEST 1234 5698 7654 32",
            "CUI//SP-EXPT banner|CUI_MARKING|CUI//SP-EXPT",
            "portion (CUI) marked|CUI_MARKING|(CUI)",
            "CONTROLLED UNCLASSIFIED INFORMATION|CUI_MARKING|CONTROLLED UNCLASSIFIED INFORMATION",
            "FOR OFFICIAL USE ONLY|CUI_MARKING|FOR OFFICIAL USE ONLY",
            "legacy FOUO marking|CUI_MARKING|FOUO",
            "DISTRIBUTION STATEMENT D. Distribution authorized to DoD only.|CUI_MARKING|DISTRIBUTION STATEMENT D.",
            "subject to ITAR controls|EXPORT_CONTROLLED|ITAR",
            "classified EAR99 item|EXPORT_CONTROLLED|EAR99",
            "ECCN 5A002.a.1 applies|EXPORT_CONTROLLED|ECCN 5A002.a.1",
            "This data is export-controlled.|EXPORT_CONTROLLED|export-controlled"
    })
    void detectsEveryCategory(String text, Category category, String expected) {
        assertThat(values(text, category)).contains(expected);
    }

    @ParameterizedTest(name = "no {1} in: {0}")
    @CsvSource(delimiter = '|', value = {
            "invalid area 000-12-3456|SSN",
            "invalid area 666-12-3456|SSN",
            "ITIN range 912-70-1234|SSN",
            "zero group 123-00-4567|SSN",
            "zero serial 123-45-0000|SSN",
            "US Patent 10,123,456 B2|SSN",
            "a range of 10-20 mm and 30-40 mm|PHONE",
            "application 19/000,123 filed|PHONE",
            "measured 3.5 x 10.2 x 12.75 mm|IP_ADDRESS",
            "version 256.1.1.1 invalid octet|IP_ADDRESS",
            "card 4111 1111 1111 1112 fails Luhn|FINANCIAL_ACCOUNT",
            "taking into account the 5 embodiments|FINANCIAL_ACCOUNT",
            "date 03/14/1985 without a label|DATE_OF_BIRTH",
            "the passport holder inserts the card|GOVERNMENT_ID",
            "the CUIRASS armour and CUISINE|CUI_MARKING",
            "DISTRIBUTION STATEMENT A. Approved for public release.|CUI_MARKING",
            "a gear 12 meshes with the Drive shaft 14|ADDRESS",
            "the email field of the form|EMAIL"
    })
    void ignoresLookalikes(String text, Category category) {
        assertThat(values(text, category)).isEmpty();
    }

    @Test
    void validators() {
        assertThat(RuleBasedDetector.luhn("79927398713")).isTrue();
        assertThat(RuleBasedDetector.luhn("79927398710")).isFalse();
        assertThat(RuleBasedDetector.validCard("1234567812345670")).isFalse(); // Luhn-valid but unknown prefix
        assertThat(RuleBasedDetector.validCard("411111111111")).isFalse();
        assertThat(RuleBasedDetector.validIpv4("10.0.0.1")).isTrue();
        assertThat(RuleBasedDetector.validIpv4("10.00.0.1")).isFalse();
        assertThat(RuleBasedDetector.validIban("GB82WEST12345698765432")).isTrue();
        assertThat(RuleBasedDetector.validIban("GB00WEST12345698765432")).isFalse();
        assertThat(RuleBasedDetector.validIban("GB82WEST")).isFalse();
        assertThat(RuleBasedDetector.validSsn("078-05-1120")).isTrue();
        assertThat(RuleBasedDetector.validSsn("12345")).isFalse();
    }

    @Test
    void findingsCarryOffsetsAndReasonsButNoValues() {
        String text = "Contact: jane@example.org, SSN 078-05-1120";
        List<Finding> findings = detector.detect(3, text);
        assertThat(findings).extracting(Finding::page).containsOnly(3);
        assertThat(findings).extracting(Finding::source).containsOnly(Source.RULES);
        assertThat(findings).allSatisfy(f -> {
            assertThat(f.reason()).doesNotContain("jane").doesNotContain("078");
            assertThat(f.length()).isPositive();
        });
    }

    @Test
    void masksFreeText() {
        assertThat(detector.mask("mail jane@example.org or call (555) 010-4477"))
                .isEqualTo("mail [REDACTED] or call [REDACTED]");
        assertThat(detector.mask("nothing sensitive")).isEqualTo("nothing sensitive");
    }
}
