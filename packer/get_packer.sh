#!/bin/bash
VERSION=1.6.0
curl https://releases.hashicorp.com/packer/${VERSION}/packer_${VERSION}_linux_amd64.zip -o ./packer.zip
unzip -oj packer.zip
rm packer.zip
./packer version