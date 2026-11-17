# SPDX-License-Identifier: MIT
# The web observation interface (web/README.md). Plain ES modules: the only
# build step is the course map, map.json, which scripts/web-map.py makes from
# the tree.
#
#   make -C web map        build map.json
#   make -C web serve      build it and serve the repository's root on PORT
#                          (open http://localhost:$(PORT)/web/)
#   make -C web check      build it, then run the map's tests and the browser
#                          tests (Playwright through uv, one browser at a time),
#                          the last two on the bundles of `make web-bundles` and
#                          `make web-view-bundles` (the views of T3 to T5)
#   make -C web examples   make the example bundles of web/examples/ again
#                          (needs the Verilator models of T1 and T2)

ROOT       := $(abspath ..)
PYTHON     ?= python3
PORT       ?= 8000
PLAYWRIGHT ?= playwright==1.63.0
BOOTCAMP_DIR ?= $(ROOT)/externals/chisel-bootcamp

# The map describes this tree (its EXERCISES.md, task directories and docs).
-include $(CURDIR)/private.mk
MAP_ROOT ?= $(ROOT)
MAP_DEPS ?=

.PHONY: map serve check check-map check-browser check-integration check-views examples
map: $(MAP_DEPS)
	@$(PYTHON) $(ROOT)/scripts/web-map.py --root $(MAP_ROOT) --web $(CURDIR) --bootcamp $(BOOTCAMP_DIR)

serve: map
	@echo "web: http://localhost:$(PORT)/web/"
	@$(PYTHON) -m http.server --directory $(ROOT) --bind 127.0.0.1 $(PORT)

check: map check-map check-browser check-integration check-views

check-map:
	@$(PYTHON) -m unittest discover -s $(CURDIR)/tests -p 'test_*.py'

check-browser:
	@uv run --no-project --with $(PLAYWRIGHT) python -I $(CURDIR)/tests/browser.py

# the waveform and board views under the site, on the bundles of web/waveform.mk
WEB_BUNDLES ?= $(ROOT)/build/web/bundles
check-integration: map
	@$(MAKE) --no-print-directory -C $(ROOT) web-bundles WEB_BUNDLES=$(WEB_BUNDLES)
	@uv run --no-project --with $(PLAYWRIGHT) python -I $(CURDIR)/tests/test_integration.py --bundles $(WEB_BUNDLES)

# the views of T3 to T5 under the site, on the bundles of web/views.mk
WEB_VIEW_BUNDLES ?= $(ROOT)/build/web/view-bundles
check-views: map
	@$(MAKE) --no-print-directory -C $(ROOT) web-view-bundles WEB_VIEW_BUNDLES=$(WEB_VIEW_BUNDLES)
	@uv run --no-project --with $(PLAYWRIGHT) python -I $(CURDIR)/tests/test_views.py --bundles $(WEB_VIEW_BUNDLES)

examples:
	@$(PYTHON) $(ROOT)/scripts/web-examples.py --out $(CURDIR)/examples
	@$(MAKE) --no-print-directory map
