CXX ?= c++
CXXFLAGS ?= -std=c++20 -O2 -Wall -Wextra -Wpedantic -Werror
CPPFLAGS ?= -Icore/include
BUILD_DIR := build
TEST_BIN := $(BUILD_DIR)/reviewfault_core_tests
DOMAIN_TEST_BIN := $(BUILD_DIR)/reviewfault_domain_tests
V2_TEST_BIN := $(BUILD_DIR)/reviewfault_v2_scheduler_tests
V3_TEST_BIN := $(BUILD_DIR)/reviewfault_v3_scheduler_tests
V4_TEST_BIN := $(BUILD_DIR)/reviewfault_v4_scheduler_tests
SHARED_LIB := $(BUILD_DIR)/libreviewfault_core.so
DYNAMIC_ABI_TEST_BIN := $(BUILD_DIR)/reviewfault_dynamic_abi_tests
SOURCES := core/src/domain.cpp core/src/scheduler.cpp core/src/scheduler_v2.cpp core/src/scheduler_v3.cpp core/src/scheduler_v4.cpp core/src/reviewfault_c.cpp

.PHONY: all test core-test schema-test clean

all: $(TEST_BIN) $(DOMAIN_TEST_BIN) $(V2_TEST_BIN) $(V3_TEST_BIN) $(V4_TEST_BIN) $(SHARED_LIB) $(DYNAMIC_ABI_TEST_BIN)

$(TEST_BIN): $(SOURCES) core/tests/scheduler_test.cpp | $(BUILD_DIR)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $(SOURCES) core/tests/scheduler_test.cpp -o $@

$(DOMAIN_TEST_BIN): $(SOURCES) core/tests/domain_test.cpp | $(BUILD_DIR)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $(SOURCES) core/tests/domain_test.cpp -o $@

$(V2_TEST_BIN): $(SOURCES) core/tests/scheduler_v2_test.cpp | $(BUILD_DIR)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $(SOURCES) core/tests/scheduler_v2_test.cpp -o $@

$(V3_TEST_BIN): $(SOURCES) core/tests/scheduler_v3_test.cpp | $(BUILD_DIR)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $(SOURCES) core/tests/scheduler_v3_test.cpp -o $@

$(V4_TEST_BIN): $(SOURCES) core/tests/scheduler_v4_test.cpp | $(BUILD_DIR)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $(SOURCES) core/tests/scheduler_v4_test.cpp -o $@

$(SHARED_LIB): $(SOURCES) | $(BUILD_DIR)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) -fPIC -shared -DREVIEWFAULT_BUILD_SHARED=1 $(SOURCES) -o $@

$(DYNAMIC_ABI_TEST_BIN): core/tests/dynamic_abi_test.cpp $(SHARED_LIB) | $(BUILD_DIR)
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) core/tests/dynamic_abi_test.cpp -ldl -o $@

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

test: core-test schema-test

core-test: $(TEST_BIN) $(DOMAIN_TEST_BIN) $(V2_TEST_BIN) $(V3_TEST_BIN) $(V4_TEST_BIN) $(DYNAMIC_ABI_TEST_BIN)
	./$(TEST_BIN)
	./$(DOMAIN_TEST_BIN)
	./$(V2_TEST_BIN)
	./$(V3_TEST_BIN)
	./$(V4_TEST_BIN)
	./$(DYNAMIC_ABI_TEST_BIN)

schema-test:
	node --no-warnings schema/tests/migration_test.mjs
	node --no-warnings schema/tests/migration_v2_test.mjs
	node --no-warnings schema/tests/migration_v3_test.mjs
	node --no-warnings schema/tests/migration_v4_test.mjs
	node --no-warnings schema/tests/harmony_migration_parser_test.mjs
	node --no-warnings schema/tests/backup_manifest_test.mjs
	node --no-warnings schema/tests/queue_contract_test.mjs
	node --no-warnings schema/tests/queue_performance_test.mjs
	node --no-warnings schema/tests/platform_contract_test.mjs
	node --no-warnings schema/tests/version_contract_test.mjs
	node --no-warnings schema/tests/replay_tool_test.mjs
	node --no-warnings schema/tests/v4_contract_test.mjs
	node --no-warnings schema/tests/backend_contract_test.mjs

clean:
	rm -rf $(BUILD_DIR)
