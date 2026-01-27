# ---------- build stage ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# copia tudo e compila
COPY . .
RUN mvn -DskipTests clean package

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Render injeta $PORT; Spring precisa ouvir nessa porta
ENV PORT=8080
EXPOSE 8080

# copia o jar gerado
COPY --from=build /app/target/compiler-api-1.0.0.jar /app/app.jar

# sobe a API
CMD ["sh", "-c", "java -Dserver.port=$PORT -jar /app/app.jar"]
