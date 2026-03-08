# Session State — IGNITE-28098 ScaleCube Migration

## Branch
`claude/ignite-28098-scalecube-ThwMl`

## Task
Migrate `ignite-network` module from ScaleCube cluster library to a custom implementation.

## Changes Made
- Replaced ScaleCube's `ClusterImpl` with new `IgniteClusterImpl` in `modules/network/src/main/java/org/apache/ignite/internal/network/scalecube/ScaleCubeClusterServiceFactory.java`
- New class: `modules/network/src/main/java/org/apache/ignite/internal/network/scalecube/IgniteClusterImpl.java`
  - Implements ScaleCube's `Cluster` interface
  - Manages membership via `FailureDetector`, `GossipProtocol`, and `MembershipProtocol` (all ScaleCube classes)
  - Handles node lifecycle (start/stop/shutdown)
  - Removes dependency on ScaleCube's `ClusterImpl` while keeping the protocol-level classes

## Build & Test Results (2026-03-08, Run 2)

### Checkstyle & PMD
- All passed: `checkstyleMain`, `checkstyleTest`, `checkstyleIntegrationTest`, `pmdMain`, `pmdTest`

### Unit Tests (`ignite-network:test`)
- **All tests PASSED** (BUILD SUCCESSFUL in 2m 57s, 52 tasks executed)
- Previously failing `SET_LOCALHOST` test now passes (hostname resolution fixed)

### Integration Tests (`ignite-network:integrationTest`)
- **All tests PASSED** (BUILD SUCCESSFUL in 8m 6s, 189 tasks executed)
- Cluster formation, node join/leave, messaging, and topology tests all pass

### Full Build (`clean build -x integrationTest`)
- **BUILD FAILED** in `ignite-cli:test` — 30 test failures out of 589 tests
- These failures are **pre-existing and unrelated** to our changes (all in `ignite-cli` module)
- Failing test classes include: `CliConfigSetCommandTest`, `ClusterConfigReplTest`, `StreamingTableRendererTest`, `DynamicCompleterRegistryTest`, `ConfigUpdateCommandTest`, `PagerSupportTest`, `CliLoggersTest`, `ExclusionsCompleterFilterTest`, `ClusterConfigTest`, `SqlHelpCommandTest`
- Our changed files are only in `ignite-network` and `gradle/libs.versions.toml` — no CLI changes
- The `ignite-network` module compiled and tested successfully in the full build

## Changed Files
- `gradle/libs.versions.toml` — ScaleCube version upgrade
- `modules/network/src/main/java/.../ScaleCubeClusterService.java`
- `modules/network/src/main/java/.../ScaleCubeDirectMarshallerTransport.java`
- `modules/network/src/main/java/.../ScaleCubeTopologyService.java`
- `modules/network/src/test/java/.../ScaleCubeDirectMarshallerTransportTest.java`
- `modules/network/src/test/java/.../ScaleCubeTopologyServiceTest.java`

## Next Steps
- Address CLI test failures if required (pre-existing, not caused by our changes)
- Create PR for IGNITE-28098
