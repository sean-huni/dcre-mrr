# MRR is the single writer of the mandate request spine rows (R-04): it reads
# one OnHost mandate instruction book per arrival and persists the header plus
# every instruction record, keyed (arrival_id, sequence) for restart safety
# (R-05), minting each row's MndtReqId deterministically write-ahead of every
# downstream side effect (R-07).
@mrr
Feature: MRR boundary reader ingests OnHost mandate instruction books
  The boundary reader parses a fixed-width mandate instruction book, applies
  the structural file-fatal tier and persists the mandate request spine.

  Scenario: A valid instruction book is persisted into the mandate request spine
    Given the book reader receives an instruction book with a create, an amend and a cancel row
    When the MRR job runs
    Then the job completes with a clean business verdict
    And the spine holds one header row and 3 entry rows for the arrival
    And the entry action codes are the canonical CREATE, AMEND and CANCEL
    And every entry rests in spine state "RECEIVED"
    And every entry carries a 35-character MndtReqId

  Scenario: Re-processing the same arrival re-mints identical MndtReqIds without duplicating
    Given the book reader receives an instruction book with a create, an amend and a cancel row
    When the MRR job runs
    And the MRR job runs again for the same arrival
    Then the job completes with a clean business verdict
    And the spine holds one header row and 3 entry rows for the arrival
    And the MndtReqIds are identical across the two runs

  Scenario: A truncated header is rejected file-fatally
    Given the book reader receives an instruction book with a truncated header
    When the MRR job runs
    Then the book is rejected file-fatally with a reason containing "header shorter"
    And no spine entries are persisted for the arrival

  Scenario: A header declaring the wrong entry count is rejected file-fatally
    Given the book reader receives an instruction book declaring 5 entries but carrying 3
    When the MRR job runs
    Then the book is rejected file-fatally with a reason containing "entry_count=5"
    And no spine entries are persisted for the arrival
