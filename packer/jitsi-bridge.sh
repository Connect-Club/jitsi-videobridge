#!/bin/bash
set -e
echo "sleeping..."
sleep 60
sudo apt-get update
sudo apt-get install openjdk-8-jdk openjdk-8-jre git unzip prometheus-node-exporter gpg wget apt-transport-https -y
java -version

# filebeat
wget -qO - https://artifacts.elastic.co/GPG-KEY-elasticsearch | sudo apt-key add -
echo "deb https://artifacts.elastic.co/packages/7.x/apt stable main" | sudo tee -a /etc/apt/sources.list.d/elastic-7.x.list
sudo apt-get update && sudo apt-get install filebeat
sudo mv /tmp/filebeat.yml /etc/filebeat/filebeat.yml
sudo chown root:root /etc/filebeat/filebeat.yml
sudo chmod go-w /etc/filebeat/filebeat.yml
sudo mv /tmp/filebeat.service /lib/systemd/system
sudo systemctl daemon-reload
sudo systemctl enable filebeat
filebeat version

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

sudo mkdir -p /var/log/jvb
sudo chmod a+w /var/log/jvb
sudo mv /tmp/videobridge.service /lib/systemd/system
sudo chmod 0644 /lib/systemd/system/videobridge.service
sudo ln -s /lib/systemd/system/videobridge.service /etc/systemd/system/multi-user.target.wants/
sudo systemctl enable videobridge.service
sudo systemctl start videobridge
sleep 5
sudo systemctl status videobridge
