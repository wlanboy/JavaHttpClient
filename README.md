# JavaHttpClient
Spring based rest service to test http routes

# build
* mvn package

# docker build
* docker build -t wlanboy/javahttpclient:latest . --build-arg JAR_FILE=./target/javahttpclient-0.0.1-SNAPSHOT.jar

# run container
* docker run -d --name httpclient --publish 8080:8080 --restart unless-stopped wlanboy/javahttpclient:latest

# test java http client
* curl 
--location 
--request POST 'http://127.0.0.1:8080/client' \
--header 'Content-Type: application/json' \
--data-raw '{
    "url" : "https://github.com",
    "copyHeaders": "false"
}'
