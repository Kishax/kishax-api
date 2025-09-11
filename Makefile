# Kishax AWS Integration - Simple Test Commands
include .env

.PHONY: test-local test-prod publish

# Test with LocalStack (development)
test-local:
	@echo "🧪 Running LocalStack development tests..."
	@if [ ! -f .env.local ]; then \
		echo "❌ .env.local not found. Please copy from template:"; \
		echo "   cp .env.local.example .env.local"; \
		exit 1; \
	fi
	@cp .env.local .env
	docker compose up -d
	@sleep 5
	RUN_INTEGRATION_TESTS=true mvn test -Dtest=LocalStackIntegrationTest
	docker compose down

# Test with real AWS (production)
test-prod:
	@echo "🚀 Running real AWS production tests..."
	@if [ ! -f .env.prod ]; then \
		echo "❌ .env.prod not found. Please copy from template:"; \
		echo "   cp .env.prod.example .env.prod"; \
		echo "   Then edit .env.prod with your AWS credentials"; \
		exit 1; \
	fi
	@cp .env.prod .env
	@echo "⚠️  Make sure .env.prod has your real AWS credentials!"
	RUN_REAL_AWS_TESTS=true mvn test -Dtest=RealAwsIntegrationTest

# Publish to Sonatype repository
publish:
	@echo "📦 Publishing kishax-aws to Sonatype repository..."
	mvn clean deploy
	@echo "✅ kishax-aws deployed successfully!"

.PHONY: run
run:
	@echo "INFO: Starting Kishax AWS SQS Worker..."
	@java -jar target/kishax-aws-1.0.0-with-dependencies.jar
