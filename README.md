# Aloeil

Aloeil (*à l’œil*) is a private, local-first journal for home eye-pressure readings.

It is **not** a medical device, diagnosis tool, or treatment system. It does not talk to tonometers: users enter readings manually, so the app can work with any home tonometer.

## Product direction

- Installed Android app, French-first, with very large touch targets
- Several measurements per eye in one sitting
- History and graphs that show recorded facts without diagnostic colouring or thresholds
- The phone is the live copy; an optional encrypted replica must never be required to save
- Optional app lock, off by default
- Open source under Apache-2.0; user data is never part of this repository

## Privacy and safety

Development and tests use synthetic values only. Never commit real readings, health records, credentials, backup exports, device identifiers, or private deployment details.

See [the approved product and privacy baseline](docs/product-privacy-baseline.md).

## Project status

The public repository and Speculoos baseline are initialized. P1 accessible capture design is ready; no app binary, cloud service, account, or health-data collection exists yet.

## License

[Apache License 2.0](LICENSE)
