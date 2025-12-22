# JavaHttpClient
Spring based rest service to test http routes in kubernetes.
Showing http status, response, timing and istio envy settings.

## Web ui
![web ui](./screenshots/httpclient-webui.png)

## Istio tab
![istio tab](./screenshots/httpclient-istiotab.png)

## Swagger
![istio tab](./screenshots/httpclient-swagger.png)

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
# local dev
```bash
curl -fsSL https://raw.githubusercontent.com/metalbear-co/mirrord/main/scripts/install.sh | bash

mirrord exec -n javahttpclient --target deployment/javahttpclient -- ./mvnw spring-boot:run
```

# swagger uri
- http://localhost:8080/swagger-ui/index.html#/http-client-controller/postMapping

# curl calls for mirrorservice
* see: https://github.com/wlanboy/MirrorService
```bash
curl -X 'POST' \
  'http://localhost:8080/client' \
  -H 'accept: */*' \
  -H 'Content-Type: application/json' \
  -d '{
  "url": "http://gmk:8003/resolve/google.com",
  "method": "GET",
  "body": "",
  "copyHeaders": false
}'

curl -X 'POST' \
  'http://localhost:8080/client' \
  -H 'accept: */*' \
  -H 'Content-Type: application/json' \
  -d '{
  "url": "http://gmk:8003/mirror?request=HalloWelt&statuscode=200&wait=4",
  "method": "GET",
  "body": "",
  "copyHeaders": true
}'
```