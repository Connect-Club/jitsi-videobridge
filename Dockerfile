FROM openjdk:8-jdk
#can not use ALPINE image because of SCTP lib

COPY target/jitsi-videobridge.docker.zip /tmp/jitsi-videobridge.zip
RUN mkdir /app \
    && unzip /tmp/jitsi-videobridge.zip -d /app \
    && rm /tmp/jitsi-videobridge.zip

EXPOSE 10000/udp

ENV JAVA_SYS_PROPS=-Dorg.ice4j.ice.harvest.STUN_MAPPING_HARVESTER_ADDRESSES=meet-jit-si-turnrelay.jitsi.net:443

ENTRYPOINT ["/bin/bash", "/app/jvb.docker.sh", "--apis=rest"]
