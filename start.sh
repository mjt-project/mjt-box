#!/bin/bash
cd server_testing-DoNOTPUSH
cp ../target/server.jar ./

cd "$(dirname "$0")"
java -jar server.jar "$@"
