FROM ubuntu:24.04

# Install necessary tools
RUN apt-get update && apt-get install -y wget tar && rm -rf /var/lib/apt/lists/*

# Download and install JDK 25
RUN wget -q https://download.java.net/java/GA/jdk25.0.2/b1e0dfa218384cb9959bdcb897162d4e/10/GPL/openjdk-25.0.2_linux-x64_bin.tar.gz && \
    tar -xzf openjdk-25.0.2_linux-x64_bin.tar.gz -C /usr/local && \
    rm openjdk-25.0.2_linux-x64_bin.tar.gz

ENV JAVA_HOME=/usr/local/jdk-25.0.2
ENV PATH=$JAVA_HOME/bin:$PATH

WORKDIR /app

# Copy gradle wrapper and related files
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./

# Copy source code
COPY src src

# Make gradlew executable
RUN chmod +x gradlew

# Build the application
RUN ./gradlew bootJar --no-daemon

# Run the application
CMD ["java", "-jar", "build/libs/aml-0.0.1-SNAPSHOT.jar"]
