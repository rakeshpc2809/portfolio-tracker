#!/bin/bash
export DB_NAME=cas_db
export DB_USER=user
export DB_PASSWORD=password
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/${DB_NAME}
export SPRING_DATASOURCE_USERNAME=${DB_USER}
export SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
export PORTFOLIO_API_KEY=dev-secret-key
export OLLAMA_URL=http://localhost:11434
export OLLAMA_API_BASE=http://localhost:11434
export ZIPKIN_URL=http://localhost:9411/api/v2/spans
export CAS_PARSER_URL=http://localhost:8000
export GOOGLE_SHEET_URL=none

cd cas-injector && ./mvnw spring-boot:run -Dmaven.test.skip=true
