# syntax=docker/dockerfile:1

# ---- build ------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar \
 && java -Djarmode=tools -jar build/libs/s3-emulator-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination /extracted

# ---- runtime ----------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app \
 && mkdir -p /data/buckets && chown app:app /data/buckets
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
ENV STORAGE_ROOT=/data/buckets \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Bucket contents survive container restarts.
VOLUME ["/data/buckets"]
EXPOSE 9000
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
