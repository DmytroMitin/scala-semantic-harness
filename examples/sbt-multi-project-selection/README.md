# sbt multi-project selection fixture

This fixture proves the optional build-oracle project selector is narrower than
the aggregate root:

- `core2_13` compiles and has one passing test;
- `compileFail_2` has one compile error;
- `testFail_2` compiles and has one passing plus one failing test;
- the root aggregates all three projects.

The fixture intentionally uses project IDs with digits and underscores. Tests
exercise selected compile, errors, and test behavior, root backward
compatibility, an unknown valid project, and isolation from sibling failures.
It is product test data, not evidence of broad sbt project-matrix discovery.
