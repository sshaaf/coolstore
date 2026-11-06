# CoolStore - reproducible build/tag/push targets
IMAGE_REGISTRY ?= quay.io
IMAGE_GROUP    ?= coolstore
IMAGE_NAME     ?= coolstore
IMAGE_TAG      ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo local)
IMAGE          := $(IMAGE_REGISTRY)/$(IMAGE_GROUP)/$(IMAGE_NAME):$(IMAGE_TAG)

.PHONY: help verify unit test build run dev image push scan clean

help:           ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

verify:         ## Full build: unit + integration tests, fast-jar in target/quarkus-app
	mvn -B clean verify

unit:           ## Unit tests only (no container/DB required)
	mvn -B test

build:          ## Build the fast-jar, skipping tests
	mvn -B clean package -DskipTests

dev:            ## Quarkus dev mode (Dev Services PostgreSQL, in-memory messaging)
	mvn -B quarkus:dev

run: build      ## Run the packaged fast-jar
	java -jar target/quarkus-app/quarkus-run.jar

image:          ## Build the container image with Podman/Buildah
	podman build -t $(IMAGE) -f Containerfile .

push: image     ## Push the image to the registry
	podman push $(IMAGE)

scan: image     ## Scan the image (fail threshold: no fixable CRITICAL)
	trivy image --exit-code 1 --severity CRITICAL --ignore-unfixed $(IMAGE)

clean:          ## Remove build output
	mvn -B clean
