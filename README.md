# dcre-mrr

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register#repositories) table.

Mandates Request Reader: boundary stage of the M10 mandates flow (SCRUM-74) that ingests OnHost mandate instruction books into the mandate request spine (`mandate_request_header` / `mandate_request_entry`) in `dcre_man`.

## What it does

| | |
|---|---|
| Stage code | `MRR` (AGT `Stage.MRR`) |
| Family | Mandates (pain.009, `dcre_man`) |
| Leg | REQ |
| Trigger | Arrival-launched: an OnHost book landing in a per-client `onhost-req-man/in` directory |
| Upstream | None inside DCRE: MRR is the entry stage of AGT's `RouteDags.MAN` DAG |
| Downstream | `MRV`. On a whole-file failure AGT launches `MIR`, the route's responder, to NACK |
| Diagram sheet | `dcre-mandates-req` in the design register |

DAG position read from AGT `origin/dev` `RouteDags.java` and `Stage.java` (checked 2026-09-28): `MRR -> MRV -> MAS -> MIT -> fork {MIR, MRW}`.

MRR is the first stage of the mandates request DAG. OnHost drops a fixed-width instruction book into the per-client exchange (`onhost-req-man/in`), AGT registers the arrival and launches MRR as a short-lived Kubernetes Job with `arrival.id` as the identifying JobParameter (R-16). MRR parses the header, runs the whole-file structural tier, then ingests every instruction record (action codes CRE|AMD|CAN canonicalized to CREATE|AMEND|CANCEL); it is the single writer of the spine ROWS (R-04), while MRV/MAS/MIT advance the `spine_state` column they own per stage. Route: `MRR -> MRV -> MAS -> MIT -> { MIR || MRW }`.

Each entry's `MndtReqId` is minted DETERMINISTICALLY as a SHA-256 digest over the full identity tuple (client, mandate_ref, action_code, msg_id), with each component length-prefixed before hashing so component boundaries are part of the digest (no delimiter ambiguity), shape `MRQ` + 32 hex chars (35 = ISO 20022 max), and persisted at ingest, write-ahead of every downstream side effect (R-07): a byte-verbatim replay re-mints the identical id, so the UNIQUE constraint arbitrates duplicate submissions instead of minting drift.

### Duplicate and collision handling (B1)

Two same-(mandate_ref, action_code) rows in ONE book would mint the IDENTICAL `MndtReqId` and poison the global `uq_man_req_entry_mndt_req_id` UNIQUE mid-chunk. MRR fixes this in two layers:

- **First-wins intra-file dedup (ingest):** `MandateBookReaderService` scans the detail rows single-threaded during ingest; the FIRST occurrence (lowest sequence) mints normally, every LATER occurrence is persisted with `mndt_req_id` NULL (NULLs are exempt from the UNIQUE in CockroachDB) and the MRR-owned column `dup_in_file = true`. The duplicate-sequence set is ferried to the partitioned entry writer through the job execution context, so a later duplicate lands flagged regardless of which partition holds it. **MRV (T4) emits `FAIL_DUPLICATE_REF` (platform-model `MandateOutcome`) for `dup_in_file` rows.** Deterministic across byte-verbatim replays (ordered by sequence).
- **Cross-arrival collision (defense-in-depth):** a resubmit under a NEW arrival that shares the msg_id re-mints identical ids even when only a later/reordered row overlaps a prior arrival. An ingest pre-flight mints EVERY first-occurrence id of the book (all first-wins winners) and checks them against other arrivals in one IN-list scan; on any match it rejects the whole book as a business `FILE_FATAL` (NACK via MIR, zero rows persisted). A residual write-race is also caught at the entry DAO: a `DuplicateKeyException` on the mndt_req_id UNIQUE is converted to a diagnosed `FileFatalException` rather than a raw CockroachDB crash loop (in the partitioned detail step this remains a TECH-tier job death, see below). AGT R-16 quarantine is the primary guard upstream; these are the belt.

**Content-hash dedup is deliberately NOT built here (deferred, plan T3 / R-41 analog):** a filename-independent identical-bytes no-op keyed on an arrival-level content hash is an AGT-tier concern (R-16 quarantine); MRR's belt is the deterministic `MndtReqId` UNIQUE plus the collision pre-flight above.

## Architecture and principles

Ephemeral Spring Boot 4.1.0 / Spring Batch 6 / Java 25 batch job cloned from the CRR skeleton: `ExitCodeMain` wires the Batch outcome into the JVM exit code (R-34), CockroachDB via the PostgreSQL driver, layer-first packages (`batch/`, `config/`, `data/model/`, `data/repo/`, `domain/`, `service/`).

1. `headerStep` (tasklet): `BookHeaderTasklet -> MandateBookReaderService` parses line 1 against `MandateLayouts.HEADER` (SYNTHETIC-CONTRACT, A-61; content length 109) and runs the ingest tier: the structural checks (empty file, header length, non-numeric header fields, declared entry_count vs actual lines, R-31 filename-client vs header destination), the first-wins intra-file duplicate scan (B1), and the cross-arrival `MndtReqId` collision pre-flight (B1). Any of these failing exits `FILE_FATAL`: a business verdict (job COMPLETED, seam `BUSINESS_FILE_FATAL`, zero rows persisted), never a process death. Carries the shared `CrdbRetryExceptionHandler` (40001 re-runs the tasklet).
2. `detailStep` (partitioned manager/worker): `LineRangePartitioner` splits the detail records into contiguous byte-offset ranges (byte counts, never charset decodes; only `MandateLayouts.DETAIL` LRECL 285 is accepted, a collections copybook fails closed). Workers (`detailWorkerStep`, chunk 100) read with `FixedRecordRangeReader` (ISO_8859_1 byte-transparent, restart resumes mid-range via `read.count`, R-05) into `MandateEntryWriter`, which parses amounts through `MoneyText` at `dcre.amount-scale`, mints the MndtReqId (NULL + `dup_in_file` for a first-wins intra-file duplicate) and upserts via `MandateRequestEntryBatchDao` `ON CONFLICT (arrival_id, sequence) DO UPDATE`. `spine_state` is absent from both the insert list (DB default RECEIVED) and the update set, so replays never clobber downstream stage advancement. The 40001 retry is deliberately NOT on the worker step (a swallowed commit abort would drop the in-flight chunk; verified in CRR 2026-07-14).

**Fatal-tier honesty (m1):** only the INGEST tier (header structural checks + the collision pre-flight) produces business `FILE_FATAL` verdicts (NACK via MIR). A structural fault discovered in the partitioner/reader inside the partitioned detail step (a wrong-LRECL record, a record overrunning its byte layout) is NOT a NACK: it is a **TECH-tier job death**, the worker step fails, and the non-zero exit code plus the K8s Failed condition are the witnesses (R-33). The entry DAO's collision belt is likewise a TECH-tier death in the detail step; it only guarantees a diagnosed failure, not a raw crash loop.

`BatchMetaConfig` sweeps stale `MRR_BATCH_` executions to ABANDONED before the runner fires (A-39a).

### Database

One datasource for business data: `dcre_man` via `DCRE_DB_URL` / `DCRE_DB_USER` / `DCRE_DB_PASSWORD`. A second datasource (`DCRE_AGTOPS_DB_URL` / `DCRE_AGTOPS_DB_USER` / `DCRE_AGTOPS_DB_PASSWORD`, platform-batch `HeartbeatDatasourceConfig`) carries only the `HeartbeatWriter` liveness stamp into `agt_ops`.

- Writes: `mandate_request_header` (`INSERT ... ON CONFLICT (arrival_id) DO UPDATE`), `mandate_request_entry` (`ON CONFLICT (arrival_id, sequence) DO UPDATE`, `spine_state` never in the update set), and its own `MRR_BATCH_*` metadata.
- Reads: `mandate_request_entry` for the cross-arrival `MndtReqId` collision pre-flight (`mndt_req_id IN (...) AND arrival_id <> :arrivalId`).
- `spine_state`: MRR never transitions it; new rows take the column default `RECEIVED`.
- Outcome seam: `OutcomeSeamListener("mrr", ...)` writes `BUSINESS_ACCEPTED` or `BUSINESS_FILE_FATAL` to `<DCRE_EXCHANGE_ROOT>/outcomes/<JOB_NAME>` (`local-mrr-<executionId>` when `JOB_NAME` is unset, as `MrrJobTest` asserts).

Liquibase owns the schema in the shared `dcre_man`, per-service history tables (`mrr_databasechangelog` / `mrr_databasechangeloglock`), calendar layout `2026/07/`:

- `000-man-core-bootstrap.xml`: MARK_RAN-guarded convergence creates of the shared core tables: `account_type` (seeded CHQ/SAV/TRN/CC/RF, `mandates_allowed` false only for SAV, AG01 semantics), `account`, and `mandate_reason_code` (seeded with nine coded actions, R-21). It creates no `mandate` projection table. The guards exist because dcre-infra `scripts/seed-man-core.sql` and the other mandates services can create the same tables first; the file is structurally identical (comments aside) to the copies in mrv, mas, mit and mir (checked 2026-09-28).
- `001-man-spine.xml`: `mandate_request_header` (UNIQUE arrival_id, raw + canonical msg_id per R-15) and `mandate_request_entry` (UNIQUE (arrival_id, sequence), UNIQUE mndt_req_id which is NULLABLE so a first-wins intra-file duplicate lands NULL, `dup_in_file` BOOLEAN default false for MRV's `FAIL_DUPLICATE_REF`, spine_state default RECEIVED).
- `002-batch-metadata.xml`: Liquibase-owned Spring Batch 6 metadata as typed XML, one changeset per object (six tables, three sequences), prefixed `MRR_BATCH_`, EXIT_MESSAGE widened to TEXT (A-39b).

## Prerequisites

- Java 25: `.sdkmanrc` pins `java=25-tem` (`sdk env`); `build.gradle` sets source/target compatibility 25.
- Gradle 9.5.1 through the committed wrapper (`gradle/wrapper/gradle-wrapper.properties`).
- Docker: Testcontainers CockroachDB for the tests, and the image build.
- Platform libs in Maven Local (`repositories { mavenCentral(); mavenLocal() }`): `za.co.fnb.dcre:platform-copybook:0.2.0` (`MandateLayouts`), `platform-persistence:0.1.0` and `platform-batch:0.1.0`. `MoneyText` (`platform.model`) and `R31Filename` (`platform.files`) are imported but not declared, so they arrive transitively.
- A reachable CockroachDB for a real local run (committed defaults: `localhost:26257`, databases `dcre_man` and `agt_ops`).

## Quickstart

Clean clone, no `.env` needed (working dev defaults committed in `application.yml`):

```bash
./gradlew test          # full suite, Docker required
./gradlew bootJar       # build/libs/mrr-2.0.jar

java -jar build/libs/mrr-2.0.jar \
  'arrival.id=<uuid>,java.lang.String,true' \
  'input.file=/path/to/book.txt,java.lang.String,false' \
  'original.name=FNBCC01_MNDT2026072212000001.txt,java.lang.String,false'
```

## Configuration

All keys live in `src/main/resources/application.yml` (the only profile). Spring relaxed binding lets any property be overridden by its environment-variable form (`dcre.mrr.max-partitions` by `DCRE_MRR_MAX_PARTITIONS`, and so on), so this table is the documented set, not a closed total.

| Env | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable` | Mandates DB (CockroachDB) |
| `DCRE_DB_USER` | `root` | DB user |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat datasource |
| `DCRE_AGTOPS_DB_USER` | `root` | Heartbeat DB user |
| `DCRE_AGTOPS_DB_PASSWORD` | (empty) | Heartbeat DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Exchange root for the outcome seam file |
| `DCRE_AMOUNT_SCALE` | `2` | MoneyText scale (A-1 open) |
| `DCRE_MRR_MAX_PARTITIONS` | `5` | Upper bound on the detailStep partition grid |
| `JOB_NAME` | unset (`local-mrr-<executionId>`) | Set by AGT on the K8s Job; names the outcome seam file |

Fixed in yml (no env placeholder): `dcre.batch.table-prefix: MRR_BATCH_`, Liquibase history tables `mrr_databasechangelog` / `mrr_databasechangeloglock`, virtual threads on.

JobParameters: `arrival.id` (identifying, R-16), `input.file` and `original.name` (non-identifying).

## Testing

`./gradlew test` (Docker required; `useJUnitPlatform()` with no filter, so the Testcontainers and Cucumber suites run in the same task). Testcontainers image: `cockroachdb/cockroach:v26.2.3` (`MrrJobTest`, `CucumberSpringConfig`).

- `MrrJobTest` (generated book -> 1 header + 3 canonical entries with MoneyText scaling and 35-char MndtReqIds; same-identity refusal; byte-verbatim replay row-identical incl. MndtReqIds; truncated header / non-numeric count / count mismatch all FILE_FATAL with zero entries; intra-file duplicate ref first-wins with NULL id + `dup_in_file` and deterministic replay; cross-arrival resubmit collision, incl. a later-row-only partial overlap, is a business FILE_FATAL, not a TECH crash; seam verdicts byte-exact).
- `CucumberSuiteTest`: BDD scenarios in `features/mrr_book_reader.feature` (tag `@mrr`).
- Unit: `MndtReqIdMinterTest` (incl. length-prefixed delimiter ambiguity), `IntraFileDuplicatesTest` (first-wins dedup + CSV round-trip), `LineRangePartitionerTest`, `FixedRecordRangeReaderTest`.

Fixtures are hand-built per run by `MandateBookFixture`, self-verified against `MandateLayouts` via slice round-trips.

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-mrr:<version> .
kind load docker-image --name dcre-dev dcre-mrr:<version>
```

Image base: `eclipse-temurin:25-jre-alpine` (`Dockerfile` copies `build/libs/mrr-2.0.jar`). AGT launches MRR as a K8s Job in the mandates flow namespace (`AGT_NAMESPACE_MAN`, default `dcre-man`) for each claimed `onhost-req-man` arrival, with the image from `AGT_MRR_IMAGE` (empty default = launch-disabled) and args `arrival.id=<uuid>`, `input.file=<claimed path>,java.lang.String,false`, `original.name=<physical filename>,java.lang.String,false`. AGT injects `JOB_NAME`, `DCRE_DB_URL` (from `AGT_MAN_SERVICE_DB_URL`, default `dcre_man` on `crdb.dcre.svc.cluster.local`), `DCRE_EXCHANGE_ROOT=/exchange` (the `dcre-exchange` PVC), `DCRE_AGTOPS_DB_URL` and `DCRE_AGTOPS_DB_USER` (AGT `origin/dev` `JobLauncher.java` and `application.yml`, checked 2026-09-28). The cluster itself, and the fleet-wide image switch that sets every `AGT_<STAGE>_IMAGE`, live in dcre-infra.

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
