include ../config.mk

BATCH_LATEST = gcr.io/$(PROJECT)/batch:latest
BATCH_IMAGE = gcr.io/$(PROJECT)/batch:$(shell docker images -q --no-trunc batch | sed -e 's,[^:]*:,,')

BATCH_WORKER_LATEST = gcr.io/$(PROJECT)/batch-worker:latest
BATCH_WORKER_IMAGE = gcr.io/$(PROJECT)/batch-worker:$(shell docker images -q --no-trunc batch-worker | sed -e 's,[^:]*:,,')

EXTRA_PYTHONPATH := ../hail/python:../gear:../web_common
PYTHON := PYTHONPATH=$${PYTHONPATH:+$${PYTHONPATH}:}$(EXTRA_PYTHONPATH) python3

.PHONY: check
check:
	$(PYTHON) -m flake8  --config ../setup.cfg batch
	$(PYTHON) -m pylint --rcfile ../pylintrc batch --score=n
	../check-sql.sh

.PHONY: build
build:
	$(MAKE) -C ../docker build
	-docker pull $(BATCH_LATEST)
	python3 ../ci/jinja2_render.py '{"service_base_image":{"image":"service-base"}}' Dockerfile Dockerfile.out
	docker build -t batch -f Dockerfile.out --cache-from batch,$(BATCH_LATEST),service-base .
	-docker pull gcr.io/$(PROJECT)/python:3.7-slim-stretch
	-docker pull $(BATCH_WORKER_LATEST)
	python3 ../ci/jinja2_render.py '{"global":{"project":"$(PROJECT)"}}' Dockerfile.worker Dockerfile.worker.out
	docker build -t batch-worker -f Dockerfile.worker.out --cache-from batch-worker,$(BATCH_WORKER_LATEST),gcr.io/$(PROJECT)/python:3.7-slim-stretch ..

.PHONY: push
push: build
	docker tag batch $(BATCH_LATEST)
	docker push $(BATCH_LATEST)
	docker tag batch $(BATCH_IMAGE)
	docker push $(BATCH_IMAGE)
	docker tag batch-worker $(BATCH_WORKER_LATEST)
	docker push $(BATCH_WORKER_LATEST)
	docker tag batch-worker $(BATCH_WORKER_IMAGE)
	docker push $(BATCH_WORKER_IMAGE)

JINJA_ENVIRONMENT = '{"code":{"sha":"$(shell git rev-parse --short=12 HEAD)"},"deploy":$(DEPLOY),"batch_image":{"image":"$(BATCH_IMAGE)"},"batch_worker_image":{"image":"$(BATCH_WORKER_IMAGE)"},"default_ns":{"name":"$(NAMESPACE)"},"batch_database":{"user_secret_name":"sql-batch-user-config"},"global":{"project":"$(PROJECT)","domain":"$(DOMAIN)","k8s_server_url":"$(KUBERNETES_SERVER_URL)"},"scope":"$(SCOPE)"}'

.PHONY: deploy
deploy: push
	! [ -z $(NAMESPACE) ]  # call this like: make deploy NAMESPACE=default
	E=$(JINJA_ENVIRONMENT) && \
	  python3 ../ci/jinja2_render.py $$E deployment.yaml deployment.yaml.out && \
	  python3 ../ci/jinja2_render.py $$E service-account.yaml service-account.yaml.out
	kubectl -n $(NAMESPACE) apply -f service-account.yaml.out
	kubectl -n $(NAMESPACE) apply -f deployment.yaml.out

.PHONY: create-build-worker-image-instance
create-build-worker-image-instance:
	-gcloud -q compute --project $(PROJECT) instances delete --zone=$(ZONE) build-batch-worker-image
	gcloud -q compute --project $(PROJECT) instances create --zone=$(ZONE) build-batch-worker-image --machine-type=n1-standard-1 --network=default --network-tier=PREMIUM --metadata-from-file startup-script=build-batch-worker-image-startup.sh --no-restart-on-failure --maintenance-policy=MIGRATE --scopes=https://www.googleapis.com/auth/cloud-platform --image=ubuntu-minimal-1804-bionic-v20200923 --image-project=ubuntu-os-cloud --boot-disk-size=10GB --boot-disk-type=pd-ssd

.PHONY: create-worker-image
create-worker-image:
	-gcloud -q compute --project $(PROJECT) images delete batch-worker-12
	gcloud -q compute --project $(PROJECT) images create batch-worker-12 --source-disk=build-batch-worker-image --source-disk-zone=$(ZONE)
	gcloud -q compute --project $(PROJECT) instances delete --zone=$(ZONE) build-batch-worker-image
