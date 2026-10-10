# Stage 1: Build fat JAR
FROM gradle:9.8.1-jdk17 AS builder
WORKDIR /app
COPY . .
RUN gradle buildFatJar --no-daemon --info

# Stage 2: Minimal runtime image
FROM eclipse-temurin:17-jre-alpine
# fontconfig — без него ImageIO/Graphics2D (даунскейл карт дистанций в UploadService)
# на Alpine падает на инициализации font-подсистемы, даже когда текст не рендерится.
RUN apk add --no-cache fontconfig ttf-dejavu
WORKDIR /app
COPY --from=builder /app/build/libs/*-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
