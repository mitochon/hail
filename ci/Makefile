include ../config.mk

CI_UTILS_LATEST = gcr.io/$(PROJECT)/ci-utils:latest
CI_UTILS_IMAGE = gcr.io/$(PROJECT)/ci-utils:$(shell docker images -q --no-trunc ci-utils | sed -e 's,[^:]*:,,')

CI_LATEST = gcr.io/$(PROJECT)/ci:latest
CI_IMAGE = gcr.io/$(PROJECT)/ci:$(shell docker images -q --no-trunc ci | sed -e 's,[^:]*:,,')

EXTRA_PYTHONPATH := ../batch:../hail/python:../gear:../web_common
PYTHON := PYTHONPATH=$${PYTHONPATH:+$${PYTHONPATH}:}$(EXTRA_PYTHONPATH) python3

.PHONY: check
check:
	$(PYTHON) -m flake8 ci
	$(PYTHON) -m pylint --rcfile ../pylintrc ci --score=n
	../check-sql.sh

.PHONY: build-ci-utils
build-ci-utils:
	$(MAKE) -C ../docker build
	-docker pull $(CI_UTILS_LATEST)
	python3 jinja2_render.py '{"service_base_image":{"image":"service-base"}}' Dockerfile.ci-utils Dockerfile.ci-utils.out
	docker build -t ci-utils -f Dockerfile.ci-utils.out --cache-from ci-utils,$(CI_UTILS_LATEST),service-base .

.PHONY: push-ci-utils
push-ci-utils: build-ci-utils
	docker tag ci-utils $(CI_UTILS_LATEST)
	docker push $(CI_UTILS_LATEST)
	docker tag ci-utils $(CI_UTILS_IMAGE)
	docker push $(CI_UTILS_IMAGE)

.PHONY: build
build:
	$(MAKE) -C ../docker build
	-docker	pull $(CI_LATEST)
	python3 jinja2_render.py '{"service_base_image":{"image":"service-base"}}' Dockerfile Dockerfile.out
	docker build -t ci -f Dockerfile.out --cache-from ci,$(CI_LATEST),service-base ..

.PHONY: push
push: build
	docker tag ci $(CI_LATEST)
	docker push $(CI_LATEST)
	docker tag ci $(CI_IMAGE)
	docker push $(CI_IMAGE)

.PHONY: deploy
deploy: push push-ci-utils
	! [ -z $(NAMESPACE) ]  # call this like: make deploy NAMESPACE=default
	python3 jinja2_render.py '{"code":{"sha":"$(shell git rev-parse --short=12 HEAD)"},"deploy":$(DEPLOY),"default_ns":{"name":"$(NAMESPACE)"},"ci_image":{"image":"$(CI_IMAGE)"},"global":{"project":"$(PROJECT)","zone":"$(ZONE)","ip":"$(IP)","domain":"$(DOMAIN)", "k8s_server_url":"$(KUBERNETES_SERVER_URL)"},"ci_utils_image":{"image":"$(CI_UTILS_IMAGE)"},"ci_database":{"user_secret_name":"sql-ci-user-config"}}' deployment.yaml deployment.yaml.out
	kubectl -n $(NAMESPACE) apply -f deployment.yaml.out
