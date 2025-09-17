# Kishax API Multi-Module - Build and Test Commands

.DEFAULT_GOAL := help

# Colors for output
RED := \033[0;31m
GREEN := \033[0;32m
YELLOW := \033[1;33m
BLUE := \033[0;34m
BOLD := \033[1m
RESET := \033[0m

# Project metadata
PROJECT_NAME := kishax-api
VERSION := 1.0.5

.PHONY: help test-local test-prod publish build-no-tests lint security-scan

help: ## Show this help message
	@echo "$(BOLD)Kishax API Multi-Module Project$(RESET)"
	@echo "$(BLUE)Available commands:$(RESET)"
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ {printf "  $(GREEN)%-15s$(RESET) %s\n", $$1, $$2}' $(MAKEFILE_LIST)

test-local: ## Run LocalStack integration tests (development)
	@echo "$(BLUE)🧪 Running LocalStack development tests...$(RESET)"
	@if [ ! -f .env.local ]; then \
		echo "$(RED)❌ .env.local not found. Please copy from template:$(RESET)"; \
		echo "   cp .env.local.example .env.local"; \
		exit 1; \
	fi
	@echo "$(YELLOW)Starting LocalStack and Redis containers...$(RESET)"
	docker compose --profile test up -d --build
	@sleep 10
	@echo "$(BLUE)Running integration tests...$(RESET)"
	RUN_INTEGRATION_TESTS=true mvn test -Dtest=LocalStackIntegrationTest
	@echo "$(YELLOW)Stopping test containers...$(RESET)"
	docker compose --profile test down
	@echo "$(GREEN)✅ LocalStack tests completed!$(RESET)"

test-prod: ## Run real AWS integration tests (production)
	@echo "$(BLUE)🚀 Running real AWS production tests...$(RESET)"
	@if [ ! -f .env.prod ]; then \
		echo "$(RED)❌ .env.prod not found. Please copy from template:$(RESET)"; \
		echo "   cp .env.prod.example .env.prod"; \
		echo "   Then edit .env.prod with your AWS credentials"; \
		exit 1; \
	fi
	@echo "$(YELLOW)⚠️  Make sure .env.prod has your real AWS credentials!$(RESET)"
	@sleep 2
	RUN_REAL_AWS_TESTS=true mvn test -Dtest=RealAwsIntegrationTest
	@echo "$(GREEN)✅ Production AWS tests completed!$(RESET)"

# =============================================================================
# Build Commands
# =============================================================================

build-no-tests: ## Build all modules without running tests
	@echo "$(BLUE)🔨 Building all modules (skipping tests)...$(RESET)"
	mvn clean install -DskipTests
	@echo "$(GREEN)✅ Build successful (tests skipped)!$(RESET)"

lint: ## Run code formatting and linting
	@echo "$(BLUE)🔍 Running code formatting...$(RESET)"
	mvn spotless:apply
	@echo "$(GREEN)✅ Code formatting completed!$(RESET)"

security-scan: ## Run security vulnerability scan
	@echo "$(BLUE)🔒 Running security scan...$(RESET)"
	mvn org.owasp:dependency-check-maven:check
	@echo "$(GREEN)✅ Security scan completed!$(RESET)"
