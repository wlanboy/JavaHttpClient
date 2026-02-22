# Spring Boot Layered JAR – Strategie & Anwendungsanleitung

## Warum Layering?

Docker-Images bestehen aus aufeinander gestapelten Layern. Ändert sich ein Layer, werden alle darüber liegenden Layer neu erstellt – unabhängig davon, ob sie sich selbst verändert haben. Ein gut strukturiertes Layered JAR sorgt dafür, dass bei einem typischen Deployment (nur App-Code ändert sich) die großen Dependency-Layer aus dem Cache kommen und nur die kleinen oberen Layer neu übertragen werden.

**Ergebnis:** Schnellere Builds, schnellere Deploys, geringerer Netzwerk-Traffic.

---

## Schritt 1 – layers.xml anlegen

**Pfad:** `src/main/resources/layers.xml`

Das ist die Vorlage. Kommentare beschreiben, was je nach Projekt angepasst werden muss.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<layers xmlns="http://www.springframework.org/schema/boot/layers"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.springframework.org/schema/boot/layers
                            https://www.springframework.org/schema/boot/layers/layers-3.0.xsd">
    <application>
        <!-- Spring Boot Launcher separat – ändert sich nur bei Spring Boot Version-Update -->
        <into layer="spring-boot-loader">
            <include>org/springframework/boot/loader/**</include>
        </into>

        <!-- Ressourcen getrennt vom Kompilat.
             Konfigurationsänderungen invalidieren nicht den Class-Layer.
             Anpassen: Dateiendungen und Pfade je nach Projekt ergänzen/entfernen.
             Thymeleaf-Projekte: templates/** hinzufügen.
             Projekte ohne Templates: die templates-Zeile weglassen. -->
        <into layer="application-resources">
            <include>BOOT-INF/classes/**/*.yml</include>
            <include>BOOT-INF/classes/**/*.yaml</include>
            <include>BOOT-INF/classes/**/*.properties</include>
            <include>BOOT-INF/classes/**/*.xml</include>
            <!-- nur wenn Thymeleaf / andere Template-Engines vorhanden: -->
            <include>BOOT-INF/classes/templates/**</include>
        </into>

        <!-- Kompilierter Code + AOT-Metadaten – ändert sich am häufigsten -->
        <into layer="application" />
    </application>

    <dependencies>
        <!-- SNAPSHOT-Dependencies getrennt (zukunftssicher, auch wenn aktuell keine vorhanden) -->
        <into layer="snapshot-dependencies">
            <include>*:*:*SNAPSHOT</include>
        </into>

        <!-- Observability-Stack: eigener Release-Zyklus, unabhängig von Spring-Core.
             Nur einfügen wenn Micrometer / Prometheus / SpringDoc / OpenTelemetry im Projekt vorhanden.
             Weglassen wenn das Projekt kein Monitoring hat. -->
        <into layer="observability-dependencies">
            <include>io.micrometer:*</include>
            <include>io.prometheus:*</include>
            <include>org.springdoc:*</include>
            <include>io.opentelemetry:*</include>
        </into>

        <!-- Alle übrigen stabilen Release-Bibliotheken (Spring, Jetty/Tomcat, …) -->
        <into layer="dependencies" />
    </dependencies>

    <!-- Reihenfolge: stabilste Layer zuerst → bestes Docker-Cache-Verhalten.
         Jeder Layer der oben definiert wird MUSS hier aufgeführt sein. -->
    <layerOrder>
        <layer>dependencies</layer>
        <layer>observability-dependencies</layer>   <!-- entfernen wenn kein Observability-Layer -->
        <layer>spring-boot-loader</layer>
        <layer>snapshot-dependencies</layer>
        <layer>application-resources</layer>
        <layer>application</layer>
    </layerOrder>
</layers>
```

### Wichtige Regeln

- **Reihenfolge der XML-Elemente ist fest:** `<application>` → `<dependencies>` → `<layerOrder>`
- Jeder Layer in `<layerOrder>` muss irgendwo in `<application>` oder `<dependencies>` befüllt werden
- `<into>` ohne `<include>` fängt alles auf, was die vorherigen `<into>`-Blöcke nicht gematcht haben (Catch-All)
- Muster für Dependencies: `groupId:artifactId` – Wildcards mit `*` möglich (`io.micrometer:*`)
- Muster für Application-Inhalte: Ant-Style-Pfade (`BOOT-INF/classes/**/*.yml`)

---

## Schritt 2 – pom.xml anpassen

Im `spring-boot-maven-plugin` die Layers-Konfiguration aktivieren und auf die `layers.xml` zeigen:

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
            <configuration>${project.basedir}/src/main/resources/layers.xml</configuration>
        </layers>
    </configuration>
    <executions>
        <!-- bestehende executions bleiben erhalten -->
    </executions>
</plugin>
```

---

## Schritt 3 – Dockerfile anpassen

### Build Stage: JAR extrahieren

```dockerfile
RUN cp target/PROJEKTNAME-VERSION.jar app.jar && \
    java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted
```

`--launcher` ist in Spring Boot 4.x erforderlich, damit der Loader extrahiert wird.

### Runtime Stage: Layer kopieren

Die COPY-Befehle müssen **exakt der `<layerOrder>`** in der `layers.xml` entsprechen (stabilstes zuerst):

```dockerfile
COPY --from=build --chown=USER:USER /app/extracted/dependencies/ ./
COPY --from=build --chown=USER:USER /app/extracted/observability-dependencies/ ./   # nur wenn vorhanden
COPY --from=build --chown=USER:USER /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=USER:USER /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=USER:USER /app/extracted/application-resources/ ./
COPY --from=build --chown=USER:USER /app/extracted/application/ ./
```

`USER` ersetzen: z. B. `185` (UBI/OpenJDK), `65532` (distroless nonroot).

---

## Schritt 4 – Dockerfile25Jlink: Sonderfälle

Bei Verwendung von `jdeps` und AppCDS müssen alle Dependency-Layer im Classpath stehen:

### jdeps-Aufruf

```dockerfile
RUN jdeps \
    --ignore-missing-deps \
    --print-module-deps \
    --multi-release 25 \
    --recursive \
    --class-path "extracted/dependencies/*:extracted/observability-dependencies/*:extracted/snapshot-dependencies/*" \
    extracted/application/BOOT-INF/classes > modules.txt
```

### CDS-Training

```dockerfile
RUN /custom-jre/bin/java -XX:ArchiveClassesAtExit=app.jsa \
         -Dspring.context.exit=onRefresh \
         -Dspring.aot.enabled=true \
         -cp "extracted/dependencies/*:extracted/observability-dependencies/*:extracted/snapshot-dependencies/*:extracted/application/" \
         org.springframework.boot.loader.launch.JarLauncher || [ -f app.jsa ]
```

`application-resources` gehört **nicht** in den Classpath — die Ressourcen werden zur Laufzeit über den `JarLauncher` aus `BOOT-INF/classes/` geladen, nicht direkt über `-cp`.

---

## Referenz: Layer-Übersicht

| Layer | Inhalt | Wann anpassen |
|---|---|---|
| `dependencies` | Spring, Jetty/Tomcat, Thymeleaf, Reactor, … | Nie – Catch-All für stabile Libs |
| `observability-dependencies` | Micrometer, Prometheus, SpringDoc, OTel | Weglassen wenn kein Monitoring |
| `spring-boot-loader` | `org/springframework/boot/loader/**` | Nie |
| `snapshot-dependencies` | Alle `*SNAPSHOT`-Deps | Nie |
| `application-resources` | yml, yaml, properties, xml, templates | Dateiendungen je nach Projekt |
| `application` | Kompilierter Code, AOT-Metadaten | Nie – Catch-All für App-Content |

## Entscheidungsbaum: Welche Layer brauche ich?

```
Hat das Projekt Micrometer/Prometheus/SpringDoc?
├── Ja  → observability-dependencies Layer anlegen
└── Nein → weglassen, direkt zu dependencies

Hat das Projekt Template-Engines (Thymeleaf, FreeMarker)?
├── Ja  → templates/** in application-resources aufnehmen
└── Nein → Zeile weglassen

Wird jlink / AppCDS verwendet?
├── Ja  → observability-dependencies im jdeps- und CDS-Classpath ergänzen
└── Nein → nur COPY-Befehle im Dockerfile anpassen
```
