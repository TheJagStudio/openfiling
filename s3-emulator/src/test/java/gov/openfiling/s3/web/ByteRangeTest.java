package gov.openfiling.s3.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import gov.openfiling.s3.S3Exception;

class ByteRangeTest {

    @Test
    void parsesSingleRanges() {
        assertThat(ByteRange.parse("bytes=0-4", 10)).contains(new ByteRange(0, 4));
        assertThat(ByteRange.parse("bytes=5-", 10)).contains(new ByteRange(5, 9));
        assertThat(ByteRange.parse("bytes=-3", 10)).contains(new ByteRange(7, 9));
        assertThat(ByteRange.parse("bytes=-30", 10)).contains(new ByteRange(0, 9));
        assertThat(ByteRange.parse("bytes=8-100", 10)).contains(new ByteRange(8, 9));
        assertThat(new ByteRange(2, 5).length()).isEqualTo(4);
    }

    @Test
    void ignoresInvalidOrMultiRanges() {
        assertThat(ByteRange.parse(null, 10)).isEmpty();
        assertThat(ByteRange.parse("bytes=0-1,3-4", 10)).isEmpty();
        assertThat(ByteRange.parse("items=0-1", 10)).isEmpty();
        assertThat(ByteRange.parse("bytes=-", 10)).isEmpty();
        assertThat(ByteRange.parse("bytes=5-2", 10)).isEmpty();
        assertThat(ByteRange.parse("bytes=99999999999999999999-", 10)).isEmpty();
    }

    @Test
    void unsatisfiableRangesAre416() {
        assertThatThrownBy(() -> ByteRange.parse("bytes=10-", 10)).isInstanceOfSatisfying(S3Exception.class,
                e -> assertThat(e.status()).isEqualTo(416));
        assertThatThrownBy(() -> ByteRange.parse("bytes=-0", 10)).isInstanceOf(S3Exception.class);
        assertThatThrownBy(() -> ByteRange.parse("bytes=-5", 0)).isInstanceOf(S3Exception.class);
    }
}
