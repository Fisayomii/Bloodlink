# Build: docker build -t bloodlink-api .
# Run:   docker run -p 8080:8080 -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/bloodlink bloodlink-api

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# Dependencies first, so code changes reuse the cached layer.
COPY pom.xml .
COPY config ./config
RUN mvn -B -q dependency:go-offline
COPY src ./src
# The contract is packaged into the jar and served at /openapi.yaml for the Swagger page.
COPY contract/openapi.yaml ./contract/openapi.yaml
# Tests run in CI before any deploy; the image build only packages.
RUN mvn -B -q package -DskipTests -Dcheckstyle.skip
# Unpack the jar into plain files (libraries + application). Starting from unpacked files is faster than from one fat
# jar, and the startup cache below needs a fixed class path.
RUN java -Djarmode=tools -jar target/app.jar extract --destination /src/extracted

FROM eclipse-temurin:25-jre
WORKDIR /app
# The service runs as an unprivileged user, which also owns /app so the build step below can write the startup cache.
RUN useradd --system --uid 10001 app && chown app:app /app
COPY --from=build --chown=app /src/extracted/ /app/
USER app

# Sized for a 512 MB free container: 350 MB heap leaves room for metaspace, thread stacks and the JVM itself.
# SerialGC uses the least memory; TieredStopAtLevel=1 shortens cold starts after the host puts the service to sleep.
ENV JAVA_TOOL_OPTIONS="-Xmx350m -Xss512k -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"

# Faster cold starts: a practice start during the build records which classes the service loads and links, into an
# ahead-of-time cache (Java 25, JEP 483/514/515). Every real start then reads them from the cache instead of finding,
# parsing and linking them again. The practice start needs no database: spring.context.exit=onRefresh stops as soon as
# the application is wired, before it serves anything, and migrations are switched off for it. If the cache ever does
# not match, the JVM ignores it and starts normally, only slower.
RUN java -XX:AOTCacheOutput=/app/app.aot \
        -Dspring.context.exit=onRefresh \
        -Dspring.liquibase.enabled=false \
        -jar /app/app.jar

ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]
