package gov.openfiling.s3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Local, SigV4-verifying Amazon S3 stand-in for OpenFiling (port 9000). */
@SpringBootApplication
public class S3EmulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(S3EmulatorApplication.class, args);
    }
}
