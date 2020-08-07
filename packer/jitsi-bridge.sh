#!/bin/bash
sudo apt update
sudo apt install openjdk-11-jdk openjdk-11-jre git -y
java -version

cd
git clone $GIT_URL
cd connect-club-videobridge/
git checkout $GIT_SHA
./gradlew clean assemble
sudo cp build/libs/connect-club-videobridge-0.0.1-SNAPSHOT.jar /opt/connect-club-videobridge.jar
cd ..
rm -rf connect-club-videobridge
sudo apt purge git -y

sudo mv /tmp/videobridge.service /etc/systemd/system/videobridge.service
sudo chmod 0644 /etc/systemd/system/videobridge.service
sudo systemctl start videobridge
