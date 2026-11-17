# SPDX-License-Identifier: MIT
# Browser tests of the waveform view and the virtual teaching board, for the
# top-level Makefile (`include web/waveform.mk`).
#
#   make web-bundles        the observation bundles the tests replay, in
#                           $(WEB_BUNDLES): uart on T1 and T2 with the default
#                           input, uart on T1 with a text that repeats bytes
#                           (from cycle 0 and from cycle 1000), and a
#                           10,000-cycle window of muldiv on T2
#   make check-web-waveform the Playwright tests of web/waveform/
#   make check-web-board    the Playwright tests of web/board/
#
# The tests run one headless Chromium at a time (web/waveform/tests/runner.py).
# PLAYWRIGHT_VERSION is the release whose Chromium (revision 1243) is the one
# installed under ~/.cache/ms-playwright; another release needs its own browser.

WEB_BUNDLES        ?= $(BUILD)/web/bundles
PLAYWRIGHT_VERSION ?= 1.63.0
WEB_PYTHON         := uv run --no-project --with playwright==$(PLAYWRIGHT_VERSION) python -I
# the uart program echoes its input until Ctrl-D (\004); "hello, world" repeats l and o
WEB_UART_REPEAT    := hello, world\n\004

# web_bundle <task dir> <model> <program> <name> [make arguments]: `make obs` and a copy of its
# bundle, with the run's retire log next to it (<name>.log), which the tests check against
define web_bundle
$(MAKE) -C $(1) obs PROG=$(3) $(5) && rm -rf $(WEB_BUNDLES)/$(4) && mkdir -p $(WEB_BUNDLES) && \
cp -r $(BUILD)/$(2)/obs/$(3)/bundle $(WEB_BUNDLES)/$(4) && cp $(BUILD)/$(2)/obs/$(3)/run.log $(WEB_BUNDLES)/$(4).log
endef

.PHONY: web-bundles check-web-waveform check-web-board
web-bundles:
	@mkdir -p $(WEB_BUNDLES) && printf '$(WEB_UART_REPEAT)' > $(WEB_BUNDLES)/uart-repeat.input
	@$(call web_bundle,1-single-cycle,t1,uart,t1-uart-repeat,OBS_INPUT=$(abspath $(WEB_BUNDLES))/uart-repeat.input)
	@$(call web_bundle,1-single-cycle,t1,uart,t1-uart-repeat-late,OBS_INPUT=$(abspath $(WEB_BUNDLES))/uart-repeat.input OBS_START=1000)
	@$(call web_bundle,1-single-cycle,t1,uart,t1-uart)
	@$(call web_bundle,2-rv32im,t2,uart,t2-uart)
	@$(call web_bundle,2-rv32im,t2,muldiv,t2-muldiv,OBS_CYCLES=10000)

check-web-waveform: web-bundles
	$(WEB_PYTHON) web/waveform/tests/test_waveform.py --bundles $(WEB_BUNDLES)

check-web-board: web-bundles
	$(WEB_PYTHON) web/board/tests/test_board.py --bundles $(WEB_BUNDLES)
