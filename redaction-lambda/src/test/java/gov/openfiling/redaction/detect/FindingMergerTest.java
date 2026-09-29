package gov.openfiling.redaction.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class FindingMergerTest {

    private static Finding f(int page, int start, int end, Category category, Source source) {
        return new Finding(page, start, end, category, source, category + " by " + source);
    }

    @Test
    void mergesOverlapsKeepsDisjointAndSorts() {
        List<Finding> merged = FindingMerger.merge(List.of(
                f(2, 0, 5, Category.EMAIL, Source.RULES),
                f(1, 10, 20, Category.PHONE, Source.CLAUDE),
                f(1, 15, 25, Category.PHONE, Source.RULES),
                f(1, 30, 35, Category.ADDRESS, Source.CLAUDE),
                f(1, 10, 20, Category.PHONE, Source.CLAUDE)));
        assertThat(merged).containsExactly(
                new Finding(1, 10, 25, Category.PHONE, Source.RULES, "PHONE by RULES"),
                f(1, 30, 35, Category.ADDRESS, Source.CLAUDE),
                f(2, 0, 5, Category.EMAIL, Source.RULES));
    }

    @Test
    void rulesDominateThenLongerSpan() {
        Finding claudeLong = new Finding(1, 0, 40, Category.ADDRESS, Source.CLAUDE, "address");
        Finding ruleShort = new Finding(1, 5, 10, Category.PHONE, Source.RULES, "phone");
        assertThat(FindingMerger.merge(List.of(claudeLong, ruleShort)))
                .containsExactly(new Finding(1, 0, 40, Category.PHONE, Source.RULES, "phone"));

        Finding a = new Finding(1, 0, 10, Category.PERSON_NAME, Source.CLAUDE, "name");
        Finding b = new Finding(1, 8, 30, Category.ADDRESS, Source.CLAUDE, "address");
        assertThat(FindingMerger.merge(List.of(a, b)))
                .containsExactly(new Finding(1, 0, 30, Category.ADDRESS, Source.CLAUDE, "address"));
    }

    @Test
    void adjacentSpansAndOtherPagesStaySeparate() {
        assertThat(FindingMerger.merge(List.of(f(1, 0, 5, Category.SSN, Source.RULES), f(1, 5, 9, Category.SSN, Source.RULES))))
                .hasSize(2);
        assertThat(FindingMerger.merge(List.of())).isEmpty();
    }

    @Test
    void findingValidatesItsSpan() {
        assertThatThrownBy(() -> new Finding(0, 0, 1, Category.SSN, Source.RULES, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Finding(1, 5, 5, Category.SSN, Source.RULES, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Finding(1, -1, 5, Category.SSN, Source.RULES, "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
