# Research

Notes exploring ideas that aren't settled product decisions yet — background
and rationale for experimental changes elsewhere in the repo.

## Contents

- [`m2m/`](./m2m/) — machine-to-machine (M2M) auth for the cross-team
  SLO export endpoint (`GET /api/m2m/slo`,
  `src/main/kotlin/no/bekk/routes/M2mSloRouting.kt`): concepts, the
  proposed solution with a flowchart, setup steps, and how it compares to
  the other auth mechanisms already used on endpoints in this codebase.
