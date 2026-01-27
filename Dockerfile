# imagem base com Java 21
FROM eclipse-temurin:21-jdk-jammy

# diretório de trabalho
WORKDIR /app

# copia o projeto inteiro
COPY . .

# build do projeto
RUN ./mvnw -DskipTests clean package || mvn -DskipTests clean package

# porta usada pelo Spring Boot
EXPOSE 8080

# comando de start (Render injeta $PORT)
CMD ["sh", "-c", "java -Dserver.port=$PORT -jar target/compiler-api-1.0.0.jar"]
