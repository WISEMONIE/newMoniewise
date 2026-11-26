# -------------------------------------------------
# BUILD STAGE
# -------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -Dmaven.test.skip=true

# -------------------------------------------------
# RUNTIME STAGE
# -------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
# PDFBox draws the receipt image with Java2D. Alpine has no font config
# until these packages are installed, and without them the PNG render fails.
RUN apk add --no-cache fontconfig ttf-dejavu
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"
WORKDIR /app
# Ensure this matches your actual JAR name from pom.xml
COPY --from=build /app/target/moniewise-backend-0.0.1-SNAPSHOT.jar app.jar

# Render injects a $PORT environment variable automatically.
# We tell Spring Boot to listen to whatever Render provides.
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT:-8080}"]
