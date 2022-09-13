FROM maven:3-jdk-8 as builder
WORKDIR /build
ADD pom.xml ./
ADD settings.xml /root/.m2/
RUN mvn dependency:go-offline -B
ADD . .
RUN mvn clean package

FROM openjdk:8-jdk as app
COPY --from=builder /build/target/jitsi-videobridge.docker.zip /tmp/jitsi-videobridge.zip
RUN mkdir /app \
    && unzip /tmp/jitsi-videobridge.zip -d /app \
    && rm /tmp/jitsi-videobridge.zip

ENV JAVA_SYS_PROPS="-Dorg.ice4j.ice.harvest.STUN_MAPPING_HARVESTER_ADDRESSES=meet-jit-si-turnrelay.jitsi.net:443 -Dorg.ice4j.ipv6.DISABLED=true -Dorg.jitsi.videobridge.ENABLE_REST_SHUTDOWN=true -Dorg.jitsi.videobridge.shutdown.ALLOWED_SOURCE_REGEXP=.*"

ENTRYPOINT ["/app/jvb.docker.sh", "--apis=rest"]
