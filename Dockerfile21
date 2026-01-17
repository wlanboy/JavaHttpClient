FROM eclipse-temurin:21-jre-noble
VOLUME /tmp

WORKDIR /app
ADD target/javahttpclient-0.0.1-SNAPSHOT.jar /app/javahttpclient.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/javahttpclient.jar", "--spring.config.location=file:/app/application.properties"]