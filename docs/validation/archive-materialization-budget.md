# Archive materialization admission

This candidate adds a conservative aggregate work limit before decoded records and
strings are materialized. It is a chosen admission policy, not an empirical heap
measurement, an object-size formula, or a guarantee that every Android heap and
cryptographic provider can complete every admitted archive.

The existing per-section maximum remains 100,000. The authenticated plaintext
maximum remains 16 MiB, with the existing envelope allowance. Versions 1–4,
header fields, PBKDF2/GCM parameters and AAD are unchanged. Parsing and preflight
occur only after GCM authentication. The caller's archive is immutable and the
owned plaintext buffer is cleared in the existing `finally`, including rejection.

Each prospective model construction and each UTF field consumes one policy unit.
Current-format reading and prior-version parsing each creates a payload and an
outer record. Empty UTF fields are conservatively charged too. The aggregate
maximum is **1,600,000 units**, sixteen times the existing section limit.

| v4 section | Units per item |
| --- | --- |
| Reading | 8, plus 1 if a note is present |
| Sitting | 2 |
| Prior version | 8, plus 1 if a note is present |
| Correction operation | 3 |
| Tombstone | 2 |

Version 2 has fewer UTF fields and is charged accordingly. Legacy version 1 is
preflighted before each historical string/integer decoding attempt, with work
reserved for generated sittings and numeric conversion. Direct modified-UTF
scanning validates byte structure without creating strings, preserves the
noncanonical forms accepted by `DataInputStream`, counts UTF-16 code units, and
checks truncation/trailing bytes. Structured value/note lengths mirror existing
validation. The raw UTF-code-unit total is bounded by the existing plaintext byte
limit. Collection/index construction and provider/native allocation remain outside
the unit formula; those are reasons not to describe it as a heap-byte bound.

A 100,000-reading plus 100,000-sitting baseline costs at most 1,100,000 units,
including one note per reading. The existing exact-16-MiB tombstone fixture is
also admitted. A compact full five-section archive can satisfy all former
section/byte/history rules but exceed this new aggregate budget. Some previously
accepted dense corrected/multi-section archives therefore now reject safely.
This is an intentional acceptance restriction, not full backward compatibility.
No partial restore, data replacement or storage migration is performed on rejection.
Aggregate admission failures have the dedicated
`ArchiveMaterializationLimitException` type, so resource limits can be explained
separately from invalid archive data or failed password/authentication. Per-section
and malformed-string errors retain their existing general invalid-input handling.

Export checks the same v4 aggregate policy before record validation/serialization,
so the application does not newly emit a backup that this policy would refuse.
An archive rejected solely by this policy still requires a separately approved,
compatible recovery route; do not overwrite/delete it or weaken authentication.

The new Android tests serialize compact synthetic fixtures incrementally; they
avoid retaining a second large expected object graph and never import the large
fixtures into SQLite. They cover a 100k-reading/sitting baseline, exact-budget
acceptance and one additional note-field rejection, formerly admitted dense
sections, export admission before record access, malformed counts/UTF/truncation/
trailing bytes, legacy integer decoding, modified-UTF compatibility and authentication
precedence. Existing v1–v4 and exact-byte/item boundary regressions remain relevant.
The exact-budget test also exports the decoder's accepted graph and checks that
one additional note rejects export with the same typed limit. It retires the
synthetic source buffer first and uses a delegating one-record override instead
of allocating another large model graph.

Compilation and Android execution are pending. No exhaustion or peak-heap
measurement is claimed. Any new exact-head CI result must be recorded separately.
