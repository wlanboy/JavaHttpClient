# JavaHttpClient
Spring based rest service to test http routes

# build
* mvn package

# docker build
* docker build -t wlanboy/javahttpclient:latest . --build-arg JAR_FILE=./target/javahttpclient-0.0.1-SNAPSHOT.jar

# run container
* docker run -d --name httpclient --publish 8080:8080 --restart unless-stopped wlanboy/javahttpclient:latest

# docker hub
* https://hub.docker.com/r/wlanboy/javahttpclient

# test java http client
```bash
curl -L -X POST 'http://127.0.0.1:8080/client' -H 'Content-Type: application/json' \
-d '{"url" : "https://github.com", "copyHeaders": "false"}'
```

# swagger uri
- http://localhost:8080/swagger-ui/index.html#/http-client-controller/postMapping