BASE_URL ?= http://127.0.0.1:8080

up:      ## run app + Postgres in Docker on :8080
	docker compose up --build -d
down:
	docker compose down -v
burst:   ## make burst BASE_URL=https://your-service  (ADMIN_TOKEN env if not the dev default)
	java burst/Burst.java $(BASE_URL)
