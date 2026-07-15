FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
RUN mvn -q -f backend/pom.xml dependency:go-offline
COPY backend/src backend/src
RUN mvn -q -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S autospec && adduser -S autospec -G autospec
COPY --from=build /workspace/backend/target/autospec-backend-*.jar app.jar
USER autospec
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
