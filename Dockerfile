# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
ENV MAVEN_OPTS="-Xmx400m"
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
RUN useradd -r -u 1001 app
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
