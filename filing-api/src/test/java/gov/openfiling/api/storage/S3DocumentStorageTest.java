package gov.openfiling.api.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import gov.openfiling.api.exception.UpstreamUnavailableException;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@ExtendWith(MockitoExtension.class)
class S3DocumentStorageTest {

    @Mock
    S3Client s3;

    @Mock
    S3Presigner presigner;

    S3DocumentStorage storage;

    @BeforeEach
    void setUp() {
        storage = new S3DocumentStorage(s3, presigner, Clock.systemUTC());
    }

    @Test
    void head_existing_returnsSizeTypeAndUnquotedEtag() {
        Instant modified = Instant.parse("2026-09-29T12:00:00Z");
        given(s3.headObject(any(HeadObjectRequest.class))).willReturn(HeadObjectResponse.builder()
                .contentLength(48213L).contentType("application/pdf").eTag("\"0f34\"").lastModified(modified).build());

        assertThat(storage.head("b", "k")).contains(new DocumentStorage.ObjectHead(48213, "application/pdf", "0f34", modified));
    }

    @Test
    void head_missing_returnsEmpty() {
        given(s3.headObject(any(HeadObjectRequest.class))).willThrow(NoSuchKeyException.builder().message("nope").build());

        assertThat(storage.head("b", "k")).isEmpty();
    }

    @Test
    void head_404S3Exception_returnsEmpty_otherErrorsAreUnavailable() {
        given(s3.headObject(any(HeadObjectRequest.class)))
                .willThrow(S3Exception.builder().statusCode(404).message("Not Found").build())
                .willThrow(S3Exception.builder().statusCode(500).message("boom").build())
                .willThrow(SdkClientException.create("connection refused"));

        assertThat(storage.head("b", "k")).isEmpty();
        assertThatThrownBy(() -> storage.head("b", "k")).isInstanceOf(UpstreamUnavailableException.class);
        assertThatThrownBy(() -> storage.head("b", "k")).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void readPrefix_requestsFirstBytesWithRangeHeader() {
        given(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .willReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), "%PDF-".getBytes()));

        assertThat(storage.readPrefix("b", "k", 5)).isEqualTo("%PDF-".getBytes());
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(request.capture());
        assertThat(request.getValue().range()).isEqualTo("bytes=0-4");
    }

    @Test
    void readPrefix_emptyObjectOrMissing_returnsEmptyArray() {
        given(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .willThrow(S3Exception.builder().statusCode(416).build())
                .willThrow(S3Exception.builder().statusCode(404).build())
                .willThrow(S3Exception.builder().statusCode(403).build());

        assertThat(storage.readPrefix("b", "k", 5)).isEmpty();
        assertThat(storage.readPrefix("b", "k", 5)).isEmpty();
        assertThatThrownBy(() -> storage.readPrefix("b", "k", 5)).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void delete_missingIsIdempotent_networkErrorsPropagate() {
        willThrow(NoSuchKeyException.builder().build()).willThrow(SdkClientException.create("down"))
                .given(s3).deleteObject(any(DeleteObjectRequest.class));

        storage.delete("b", "k");
        assertThatThrownBy(() -> storage.delete("b", "k")).isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void bucketHealth_reportsUpOrDown() {
        given(s3.headBucket(any(HeadBucketRequest.class)))
                .willReturn(null)
                .willThrow(S3Exception.builder().statusCode(404).build())
                .willThrow(SdkClientException.create("refused"));

        assertThat(storage.bucketHealth("b").up()).isTrue();
        assertThat(storage.bucketHealth("b")).satisfies(h -> assertThat(h.detail()).contains("404"));
        assertThat(storage.bucketHealth("b").up()).isFalse();
    }

    @Test
    void contentDisposition_escapesQuotesAndEncodesUtf8Name() {
        assertThat(S3DocumentStorage.contentDisposition("Résumé \"final\".pdf"))
                .isEqualTo("attachment; filename=\"R_sum_ 'final'.pdf\"; filename*=UTF-8''R%C3%A9sum%C3%A9%20%22final%22.pdf");
    }
}
