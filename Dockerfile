# Werkzeuge für OCR, PDF-Verarbeitung und Datenbanksicherung. Python ist nur für OCRmyPDF nötig.
FROM python:3.13-slim-trixie AS tools

# Weitere OCR-Sprachen beim Bauen ergänzen, z. B. TESSERACT_LANGS="deu eng fra"
ARG TESSERACT_LANGS="deu eng"

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      file \
      catdoc \
      ghostscript \
      pngquant \
      poppler-utils \
      postgresql-client \
      tesseract-ocr \
      tesseract-ocr-osd \
      $(for lang in $TESSERACT_LANGS; do echo "tesseract-ocr-$lang"; done) \
 && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir ocrmypdf==16.13.0

# Liest den Text aus Formaten, die DocuMan unverändert ablegt. Dieselbe Datei nutzt die Django-Fassung.
COPY src/main/python/textextract.py /usr/local/bin/documan-textextract
RUN chmod 755 /usr/local/bin/documan-textextract

# Ohne UTF-8-Locale scheitert Java an Dateinamen mit Umlauten.
ENV PYTHONDONTWRITEBYTECODE=1 \
    HOME=/tmp \
    LANG=C.UTF-8


FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build
COPY pom.xml .
RUN mvn -B -q -Pproduction dependency:go-offline
COPY src src
RUN mvn -B -q -Pproduction -Dmaven.test.skip=true package

# Eigene, schlanke Java-Laufzeit: spart rund 100 MB im Image.
RUN jlink \
      --add-modules java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.management,jdk.naming.dns,jdk.unsupported,jdk.zipfs \
      --include-locales=de,en --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /build/jre


# Testumgebung: dieselben Werkzeuge wie im Betrieb, dazu JDK, Maven und Schriften für Testbilder.
FROM tools AS test

RUN apt-get update \
 && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /opt/java/openjdk /opt/java/openjdk
COPY --from=build /usr/share/maven /usr/share/maven
ENV JAVA_HOME=/opt/java/openjdk \
    MAVEN_HOME=/usr/share/maven \
    PATH=/opt/java/openjdk/bin:/usr/share/maven/bin:$PATH
WORKDIR /workspace


FROM tools AS app

COPY --from=build /build/jre /opt/jre
COPY --from=build /build/target/documan.jar /app/documan.jar
COPY --chmod=755 src/main/docker/documan-wait-db /usr/local/bin/documan-wait-db

# Erscheint in der Oberfläche: docker build --build-arg DOCUMAN_VERSION=2026.11.02 .
ARG DOCUMAN_VERSION=dev
ENV DOCUMAN_VERSION=$DOCUMAN_VERSION \
    PATH=/opt/jre/bin:$PATH \
    JAVA_OPTS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -Duser.home=/tmp -Djava.awt.headless=true"

WORKDIR /app
USER 1000:1000
EXPOSE 8000
# Wartet zuerst auf die Datenbank, beim Hochfahren eines NAS kommt sie oft später.
ENTRYPOINT ["documan-wait-db", "sh", "-c", "exec java $JAVA_OPTS $JAVA_HEAP -jar /app/documan.jar"]
