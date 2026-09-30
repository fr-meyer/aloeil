# Aloeil

Aloeil (*à l’œil*) is a private, local-first journal for home eye-pressure readings.

It is **not** a medical device, diagnosis tool, or treatment system. It does not talk to tonometers: users enter readings manually, so the app can work with any home tonometer.

## Product direction

- Installed Android app with French, English, and Korean as the initial languages, with very large touch targets
- Several measurements per eye in one sitting
- History and graphs that show recorded facts without diagnostic colouring or thresholds
- The phone is the live copy; an optional encrypted replica must never be required to save
- A future optional app lock is planned but is not implemented in the current build; access currently relies on the phone's lock screen
- Open source under Apache-2.0; user data is never part of this repository

## Privacy and safety

Development and tests use synthetic values only. Never commit real readings, health records, credentials, backup exports, device identifiers, or private deployment details.

See [the approved product and privacy baseline](docs/product-privacy-baseline.md).

## Project status

P5 technical validation is recorded for PR 7 head `4d076cbae4a1d95d81f2ee5127739d685821b12a`. Signed 0.1.0 was installed on the intended phone; the owner confirmed restoration and phone security cleanup on 2026-09-30. Intended-user comfort/privacy acceptance and the separately consented private pilot remain open. See [the validation evidence and remaining gates](docs/validation/p5-evidence.md). The repository contains no real health data.

## License

[Apache License 2.0](LICENSE)
