#!/bin/bash
#set -e
sudo apt update
sudo apt install openjdk-8-jdk openjdk-8-jre git -y
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

sudo cat > videobridge.service <<EOF
[Unit]
Description=Videobridge
After=network.target

[Service]
ExecStart=/usr/bin/java -jar /opt/connect-club-videobridge.jar
User=nobody

[Install]
WantedBy=multi-user.target
EOF

sudo mv videobridge.service /etc/systemd/system/videobridge.service
sudo chmod 0644 /etc/systemd/system/videobridge.service
sudo systemctl start videobridge
