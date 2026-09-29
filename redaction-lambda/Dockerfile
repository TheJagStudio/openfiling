# syntax=docker/dockerfile:1
# Container-image packaging of the redaction function (alternative to the lambdaZip deployment package).
# The AWS base image ships the Lambda Runtime Interface Emulator, so the image can also be tried locally:
#   docker build -t openfiling-redaction .
#   docker run -p 9002:8080 -e S3_ENDPOINT=http://host.docker.internal:9000 \
#     -e CALLBACK_URL=http://host.docker.internal:8080/internal/v1/redactions/events openfiling-redaction
#   curl -XPOST localhost:9002/2015-03-31/functions/function/invocations -d @event.json

# ---- build ------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q lambdaZip \
 && mkdir /function && cd /function && jar xf /src/build/distributions/openfiling-redaction-lambda-0.0.1-SNAPSHOT.zip

# ---- runtime (AWS Lambda Java 21 base image) --------------------------------------------------
FROM public.ecr.aws/lambda/java:21
COPY --from=build /function/ ${LAMBDA_TASK_ROOT}/
ENV JAVA_TOOL_OPTIONS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Djava.awt.headless=true" \
    DOCUMENTS_BUCKET=openfiling-documents \
    PUBLIC_BUCKET=openfiling-public-copies \
    RENDER_DPI=150 \
    MAX_PAGES=500
CMD ["gov.openfiling.redaction.RedactionHandler::handleRequest"]
