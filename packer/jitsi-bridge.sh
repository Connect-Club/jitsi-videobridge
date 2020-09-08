#!/bin/bash
set -e
echo "sleeping..."
sleep 60
sudo apt-get update
sudo apt-get install openjdk-8-jdk openjdk-8-jre git unzip prometheus-node-exporter -y
java -version

cd
git clone $GIT_URL
cd jitsi-videobridge/
git checkout $GIT_SHA
curl https://apache-mirror.rbc.ru/pub/apache/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.zip -o maven.zip
unzip maven.zip
apache-maven-3.6.3/bin/mvn clean package
sudo unzip target/jitsi-videobridge.docker.zip -d /opt
cd ..
rm -rf jitsi-videobridge
sudo apt-get purge git -y

sudo mv /tmp/95-kibana.conf /etc/rsyslog.d/95-kibana.conf
sudo mv /tmp/videobridge.service /lib/systemd/system
sudo chmod 0644 /lib/systemd/system/videobridge.service
sudo ln -s /lib/systemd/system/videobridge.service /etc/systemd/system/multi-user.target.wants/
sudo systemctl enable videobridge.service
sudo systemctl start videobridge
sleep 5
sudo systemctl status videobridge
