FROM docker.m.daocloud.io/library/maven@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237

ARG BUILD_SHA

LABEL org.opencontainers.image.source="https://github.com/YongShuaiJi/kairo" \
      org.opencontainers.image.revision="${BUILD_SHA}" \
      org.opencontainers.image.title="Kairo V1.7 soak runner" \
      org.opencontainers.image.description="Immutable linux/amd64 runner for the real Kairo V1.7 soak harness"

WORKDIR /workspace
COPY . /workspace/

# Bind the extracted source tree to the immutable candidate without shipping repository
# history in the runtime image. run-soak.sh accepts this build-owned provenance file only
# when no Git checkout is present; normal worktree execution still derives HEAD and dirty
# state directly from Git.
RUN test -n "$BUILD_SHA" \
    && test "${#BUILD_SHA}" -eq 40 \
    && ! printf '%s' "$BUILD_SHA" | grep -q '[^0-9a-f]' \
    && printf '%s\n' "$BUILD_SHA" > /workspace/.kairo-image-build-id \
    && mvn -B --no-transfer-progress \
         -pl kairo-integration-tests -am test-compile \
    && mvn -B --no-transfer-progress \
         -pl kairo-integration-tests -am package -DskipTests \
         dependency:build-classpath \
         -Dmdep.outputFile=/tmp/kairo-soak-classpath \
         -DincludeScope=test \
    && rm -f /tmp/kairo-soak-classpath

ENV MVN="mvn -o"
ENV MAVEN_OPTS="-XX:TieredStopAtLevel=1"

ENTRYPOINT ["/workspace/scripts/v1.7/run-soak.sh"]
CMD ["--duration", "P7D", "--output", "/evidence"]
