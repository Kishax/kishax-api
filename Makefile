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
VERSION := 1.0.4

.PHONY: help test-local test-prod build clean publish docker-build docker-test

help: ## Show this help message
	@echo "$(BOLD)Kishax API Multi-Module Project$(RESET)"
	@echo "$(BLUE)Available commands:$(RESET)"
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ {printf "  $(GREEN)%-15s$(RESET) %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# =============================================================================
# Development & Testing
# =============================================================================

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

test: ## Run all unit tests (no external dependencies)
	@echo "$(BLUE)🧪 Running unit tests...$(RESET)"
	mvn test
	@echo "$(GREEN)✅ Unit tests completed!$(RESET)"

# =============================================================================
# Build Commands
# =============================================================================

build: ## Build all modules with tests
	@echo "$(BLUE)🔨 Building all modules...$(RESET)"
	mvn clean install
	@echo "$(GREEN)✅ Build successful!$(RESET)"

build-no-tests: ## Build all modules without running tests
	@echo "$(BLUE)🔨 Building all modules (skipping tests)...$(RESET)"
	mvn clean install -DskipTests
	@echo "$(GREEN)✅ Build successful (tests skipped)!$(RESET)"

build-bridge: ## Build only SQS-Redis Bridge module
	@echo "$(BLUE)🔨 Building SQS-Redis Bridge module...$(RESET)"
	mvn clean install -pl sqs-redis-bridge -am
	@echo "$(GREEN)✅ SQS-Redis Bridge build successful!$(RESET)"

build-auth: ## Build only MC Auth module
	@echo "$(BLUE)🔨 Building MC Auth module...$(RESET)"
	mvn clean install -pl mc-auth -am
	@echo "$(GREEN)✅ MC Auth build successful!$(RESET)"

clean: ## Clean all build artifacts
	@echo "$(BLUE)🧹 Cleaning build artifacts...$(RESET)"
	mvn clean
	@echo "$(GREEN)✅ Clean completed!$(RESET)"

# =============================================================================
# Docker Commands
# =============================================================================

docker-build: ## Build all Docker images
	@echo "$(BLUE)🐳 Building Docker images...$(RESET)"
	docker compose build
	@echo "$(GREEN)✅ Docker images built successfully!$(RESET)"

docker-test: ## Run tests in Docker environment
	@echo "$(BLUE)🐳 Running tests in Docker environment...$(RESET)"
	docker compose --profile test up --build --exit-code-from test-runner
	docker compose --profile test down
	@echo "$(GREEN)✅ Docker tests completed!$(RESET)"

docker-up-dev: ## Start development services (Redis + LocalStack)
	@echo "$(BLUE)🐳 Starting development services...$(RESET)"
	docker compose --profile test up -d redis localstack
	@echo "$(GREEN)✅ Development services started!$(RESET)"
	@echo "$(YELLOW)Redis: localhost:6379$(RESET)"
	@echo "$(YELLOW)LocalStack: localhost:4566$(RESET)"

docker-up-prod: ## Start production services
	@echo "$(BLUE)🐳 Starting production services...$(RESET)"
	@if [ ! -f .env.prod ]; then \
		echo "$(RED)❌ .env.prod not found. Please copy from template and configure:$(RESET)"; \
		echo "   cp .env.prod.example .env.prod"; \
		exit 1; \
	fi
	cp .env.prod .env
	docker compose --profile production up -d --build
	@echo "$(GREEN)✅ Production services started!$(RESET)"

docker-down: ## Stop all Docker services
	@echo "$(BLUE)🐳 Stopping all Docker services...$(RESET)"
	docker compose --profile test --profile production down
	@echo "$(GREEN)✅ All services stopped!$(RESET)"

# =============================================================================
# Application Runtime
# =============================================================================

run-bridge: ## Run SQS-Redis Bridge service locally
	@echo "$(BLUE)🚀 Starting SQS-Redis Bridge...$(RESET)"
	@if [ ! -f sqs-redis-bridge/target/sqs-redis-bridge-$(VERSION).jar ]; then \
		echo "$(YELLOW)JAR not found, building...$(RESET)"; \
		make build-bridge; \
	fi
	java -jar sqs-redis-bridge/target/sqs-redis-bridge-$(VERSION).jar

run-auth: ## Run MC Auth API service locally
	@echo "$(BLUE)🚀 Starting MC Auth API...$(RESET)"
	@if [ ! -f mc-auth/target/mc-auth-$(VERSION).jar ]; then \
		echo "$(YELLOW)JAR not found, building...$(RESET)"; \
		make build-auth; \
	fi
	java -jar mc-auth/target/mc-auth-$(VERSION).jar

# =============================================================================
# Publishing & Release
# =============================================================================

publish: ## Publish to Maven Central (requires proper GPG and credentials setup)
	@echo "$(BLUE)📦 Publishing $(PROJECT_NAME) to Maven Central...$(RESET)"
	@echo "$(YELLOW)⚠️  Make sure GPG signing and Sonatype credentials are configured!$(RESET)"
	mvn clean deploy -P release
	@echo "$(GREEN)✅ $(PROJECT_NAME) published successfully!$(RESET)"

release-dry-run: ## Test release process without actual deployment
	@echo "$(BLUE)🔍 Running release dry-run...$(RESET)"
	mvn clean deploy -DskipRemoteStaging=true -P release
	@echo "$(GREEN)✅ Release dry-run completed!$(RESET)"

# =============================================================================
# Code Quality & Analysis
# =============================================================================

lint: ## Run code formatting and linting
	@echo "$(BLUE)🔍 Running code formatting...$(RESET)"
	mvn spotless:apply
	@echo "$(GREEN)✅ Code formatting completed!$(RESET)"

security-scan: ## Run security vulnerability scan
	@echo "$(BLUE)🔒 Running security scan...$(RESET)"
	mvn org.owasp:dependency-check-maven:check
	@echo "$(GREEN)✅ Security scan completed!$(RESET)"

# =============================================================================
# Utility Commands
# =============================================================================

deps: ## Show dependency tree for all modules
	@echo "$(BLUE)🔍 Showing dependency tree...$(RESET)"
	mvn dependency:tree

version: ## Show current version
	@echo "$(BOLD)$(PROJECT_NAME) version: $(GREEN)$(VERSION)$(RESET)"

status: ## Show project status
	@echo "$(BOLD)📊 Project Status:$(RESET)"
	@echo "  $(BLUE)Name:$(RESET) $(PROJECT_NAME)"
	@echo "  $(BLUE)Version:$(RESET) $(VERSION)"
	@echo "  $(BLUE)Modules:$(RESET) common, sqs-redis-bridge, mc-auth"
	@echo "  $(BLUE)Java Version:$(RESET) 21"
	@echo "  $(BLUE)Maven Version:$(RESET) 3.11.0"

env-check: ## Validate environment configuration
	@echo "$(BLUE)🔍 Checking environment configuration...$(RESET)"
	@echo "Checking .env files:"
	@if [ -f .env.local.example ]; then echo "  $(GREEN)✅ .env.local.example$(RESET)"; else echo "  $(RED)❌ .env.local.example$(RESET)"; fi
	@if [ -f .env.prod.example ]; then echo "  $(GREEN)✅ .env.prod.example$(RESET)"; else echo "  $(RED)❌ .env.prod.example$(RESET)"; fi
	@if [ -f .env.local ]; then echo "  $(GREEN)✅ .env.local$(RESET)"; else echo "  $(YELLOW)⚠️  .env.local (copy from .env.local.example)$(RESET)"; fi
	@if [ -f .env.prod ]; then echo "  $(GREEN)✅ .env.prod$(RESET)"; else echo "  $(YELLOW)⚠️  .env.prod (copy from .env.prod.example)$(RESET)"; fi
