FROM docker.m.daocloud.io/library/maven@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237

ARG BUILD_SHA

LABEL org.opencontainers.image.source="https://github.com/YongShuaiJi/kairo" \
      org.opencontainers.image.revision="${BUILD_SHA}" \
      org.opencontainers.image.title="Kairo V1.7 soak runner" \
      org.opencontainers.image.description="Immutable linux/amd64 runner for the real Kairo V1.7 soak harness"

WORKDIR /workspace
COPY . /workspace/

# Keep the candidate checkout and Maven repository in the image. The runtime runner uses
# git to bind evidence to the exact commit and Maven offline mode so a seven-day gate never
# depends on network availability after the image has been pulled.
RUN test -n "$BUILD_SHA" \
    && test "$(git rev-parse HEAD^{commit})" = "$BUILD_SHA" \
    && test -z "$(git status --porcelain)" \
    && mvn -B --no-transfer-progress \
         -pl kairo-integration-tests -am test-compile \
    && mvn -B --no-transfer-progress \
         -pl kairo-integration-tests -am package -DskipTests \
         dependency:build-classpath \
         -Dmdep.outputFile=/tmp/kairo-soak-classpath \
         -DincludeScope=test \
    && rm -f /tmp/kairo-soak-classpath \
    && test -z "$(git status --porcelain)"

ENV MVN="mvn -o"
ENV MAVEN_OPTS="-XX:TieredStopAtLevel=1"

ENTRYPOINT ["/workspace/scripts/v1.7/run-soak.sh"]
CMD ["--duration", "P7D", "--output", "/evidence"]
