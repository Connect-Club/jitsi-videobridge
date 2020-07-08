FROM openjdk:8-jdk as builder
#can not use ALPINE image because of SCTP lib
WORKDIR /build
ADD https://apache-mirror.rbc.ru/pub/apache/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.zip maven.zip
ADD . .
RUN unzip maven.zip
RUN apache-maven-3.6.3/bin/mvn clean package

FROM openjdk:8-jdk as app
COPY --from=builder /build/target/jitsi-videobridge.docker.zip /tmp/jitsi-videobridge.zip
RUN mkdir /app \
    && unzip /tmp/jitsi-videobridge.zip -d /app \
    && rm /tmp/jitsi-videobridge.zip

EXPOSE 10000/udp

ENV JAVA_SYS_PROPS=-Dorg.ice4j.ice.harvest.STUN_MAPPING_HARVESTER_ADDRESSES=meet-jit-si-turnrelay.jitsi.net:443

ENTRYPOINT ["/bin/bash", "/app/jvb.docker.sh", "--apis=rest"]
