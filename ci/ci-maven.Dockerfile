FROM maven:3.9-eclipse-temurin-21

ENV DEBIAN_FRONTEND=noninteractive

# Node.js for Forgejo Actions (actions/checkout etc.)
RUN curl -fsSL https://deb.nodesource.com/setup_lts.x | bash - && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

RUN node --version && npm --version && mvn --version
