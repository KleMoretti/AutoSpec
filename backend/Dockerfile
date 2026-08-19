FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace
COPY pom.xml pom.xml
RUN mvn -q dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

# Delivery verification compiles the generated contract in-process, so the runtime
# intentionally includes javac instead of using a JRE-only image.
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
RUN addgroup -S autospec && adduser -S autospec -G autospec
COPY --from=build /workspace/target/autospec-backend-*.jar app.jar
USER autospec
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
