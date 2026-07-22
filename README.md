# dcre-mrr

Mandates Request Reader: boundary stage of the M10 mandates flow (SCRUM-74) that ingests OnHost mandate instruction books into the mandate request spine (`mandate_request_header` / `mandate_request_entry`) in `dcre_man`.

## What it does

MRR is the first stage of the mandates request DAG. OnHost drops a fixed-width instruction book into the per-client exchange (`onhost-req-man/in`), AGT registers the arrival and launches MRR as a short-lived Kubernetes Job with `arrival.id` as the identifying JobParameter (R-16). MRR parses the header, runs the whole-file structural tier, then ingests every instruction record (action codes CRE|AMD|CAN canonicalized to CREATE|AMEND|CANCEL); it is the single writer of the spine ROWS (R-04), while MRV/MAF/MIS advance the `spine_state` column they own per stage. Route: `MRR -> MRV -> MAF -> MIS -> { MIR || MRW }`.

Each entry's `MndtReqId` is minted DETERMINISTICALLY as a SHA-256 digest over the full identity tuple (client, mandate_ref, action_code, msg_id), shape `MRQ` + 32 hex chars (35 = ISO 20022 max), and persisted at ingest, write-ahead of every downstream side effect (R-07): a byte-verbatim replay re-mints the identical id, so the UNIQUE constraint arbitrates duplicate submissions instead of minting drift.

## Architecture

Ephemeral Spring Boot 4.1.0 / Spring Batch 6 / Java 25 batch job cloned from the CRR skeleton: `ExitCodeMain` wires the Batch outcome into the JVM exit code (R-34), CockroachDB via the PostgreSQL driver, layer-first packages (`batch/`, `config/`, `data/model/`, `data/repo/`, `domain/`, `service/`).

1. `headerStep` (tasklet): `BookHeaderTasklet -> MandateBookReaderService` parses line 1 against `MandateLayouts.HEADER` (SYNTHETIC-CONTRACT, A-61; content length 109) and runs the structural tier: empty file, header length, non-numeric header fields, declared entry_count vs actual lines, R-31 filename-client vs header destination. Failure exits `FILE_FATAL`: a business verdict (job COMPLETED, seam `BUSINESS_FILE_FATAL`), never a process death. Carries the shared `CrdbRetryExceptionHandler` (40001 re-runs the tasklet).
2. `detailStep` (partitioned manager/worker): `LineRangePartitioner` splits the detail records into contiguous byte-offset ranges (byte counts, never charset decodes; only `MandateLayouts.DETAIL` LRECL 285 is accepted, a collections copybook fails closed). Workers (`detailWorkerStep`, chunk 100) read with `FixedRecordRangeReader` (ISO_8859_1 byte-transparent, restart resumes mid-range via `read.count`, R-05) into `MandateEntryWriter`, which parses amounts through `MoneyText` at `dcre.amount-scale`, mints the MndtReqId and upserts via `MandateRequestEntryBatchDao` `ON CONFLICT (arrival_id, sequence) DO UPDATE`. `spine_state` is absent from both the insert list (DB default RECEIVED) and the update set, so replays never clobber downstream stage advancement. The 40001 retry is deliberately NOT on the worker step (a swallowed commit abort would drop the in-flight chunk; verified in CRR 2026-07-14).

`BatchMetaConfig` sweeps stale `MRR_BATCH_` executions to ABANDONED before the runner fires (A-39a).

## Database

Liquibase owns the schema in the shared `dcre_man`, per-service history tables (`mrr_databasechangelog` / `mrr_databasechangeloglock`), calendar layout `2026/07/`:

- `000-man-core-bootstrap.xml`: MARK_RAN-guarded pre-creates of the shared core tables (`account_type` seeded CHQ/SAV/TRN/CC/RF with `mandates_allowed` false only for SAV per AG01 semantics, A-62 pending; `account`; `mandate` projection shape, MSR sole writer per R-10; `mandate_reason_code` seeded with the nine coded actions, R-21). Canonical owners arrive with their services (R-04); whichever M-service boots first mints the shape.
- `001-man-spine.xml`: `mandate_request_header` (UNIQUE arrival_id, raw + canonical msg_id per R-15) and `mandate_request_entry` (UNIQUE (arrival_id, sequence), UNIQUE mndt_req_id, spine_state default RECEIVED).
- `002-batch-metadata.xml`: Liquibase-owned Spring Batch 6.0.4 DDL (`batch-metadata-mrr.sql`), prefixed `MRR_BATCH_`, EXIT_MESSAGE widened to TEXT (A-39b).

## Prerequisites

- Java 25 (Gradle toolchain), Docker (Testcontainers + image build)
- Platform libs `za.co.fnb.dcre:platform-{persistence,batch}:0.1.0` in Maven Local (publish chain: model -> files -> batch; persistence standalone); `MandateLayouts`/`MoneyText`/`R31Filename` arrive transitively via `platform-batch`
- A reachable CockroachDB for a real local run (committed default: `localhost:26257`, database `dcre_man`)

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

| Env | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable` | Mandates DB (CockroachDB) |
| `DCRE_DB_USER` | `root` | DB user |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../infra/dcre-infra/exchange` | Exchange root for the outcome seam file |
| `DCRE_AMOUNT_SCALE` | `2` | MoneyText scale (A-1 open) |
| `DCRE_MRR_MAX_PARTITIONS` | `5` | Upper bound on the detailStep partition grid |
| `JOB_NAME` | `local-<executionId>` | Set by AGT on the K8s Job; names the outcome seam file |

## Testing

`./gradlew test` (27 tests): `MrrJobTest` (generated book -> 1 header + 3 canonical entries with MoneyText scaling and 35-char MndtReqIds; same-identity refusal; byte-verbatim replay row-identical incl. MndtReqIds; truncated header / non-numeric count / count mismatch all FILE_FATAL with zero entries; seam verdicts byte-exact), Cucumber BDD suite (`features/mrr_book_reader.feature`, tag `@mrr`), `MndtReqIdMinterTest`, `LineRangePartitionerTest`, `FixedRecordRangeReaderTest`. Fixtures are hand-built per run by `MandateBookFixture`, self-verified against `MandateLayouts` via slice round-trips.

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-mrr:<version> .
kind load docker-image --name dcre-dev dcre-mrr:<version>
```

Image base: `eclipse-temurin:25-jre-alpine`. AGT launches MRR per registered `onhost-req-man` arrival (image from `AGT_MRR_IMAGE`, wired in M10 T9).

## Related repositories

Mandates DAG: dcre-mrr (this repo) with mrv/maf/mis/mir/mrw/mar/msr/mrg arriving through the M10 plan; skeleton source: [dcre-crr](https://github.com/sean-huni/dcre-crr); orchestrator: [dcre-agt](https://github.com/sean-huni/dcre-agt); platform libs: dcre-platform-model/files/batch/persistence; support: dcre-infra, dcre-design-register.
