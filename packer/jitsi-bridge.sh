#!/bin/bash
set -e
sudo apt-get update
sleep 2
sudo apt-get install openjdk-8-jdk openjdk-8-jre git libarchive-tools -y
java -version

cd
git clone $GIT_URL
cd jitsi-videobridge/
git checkout $GIT_SHA
curl https://apache-mirror.rbc.ru/pub/apache/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.zip -o maven.zip
bsdtar -x -f  maven.zip ./
apache-maven-3.6.3/bin/mvn clean package
sudo bsdtar -x -f target/jitsi-videobridge.docker.zip -d /opt
cd ..
rm -rf jitsi-videobridge
sudo apt-get purge git -y

sudo mv /tmp/videobridge.service /etc/systemd/system/videobridge.service
sudo chmod 0644 /etc/systemd/system/videobridge.service
sudo systemctl enable videobridge.service
sudo systemctl start videobridge
sleep 5
sudo systemctl status videobridge
