.PHONY: test build run up down logs clean

# Local development
test:
	sbt test

build:
	sbt assembly

run:
	sbt run

# Docker
up:
	docker compose up --build -d

down:
	docker compose down -v

logs:
	docker compose logs -f app

# Full clean
clean:
	sbt clean
	docker compose down -v --rmi local 2>/dev/null || true
	rm -rf target project/target
