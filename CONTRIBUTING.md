# Contributing to Mochi

Thanks for helping improve Mochi. Contributions in English or Chinese are
welcome.

## Ways to contribute

- Report reproducible bugs through
  [GitHub Issues](https://github.com/gongpx20069/hi-mochi/issues).
- Propose product features, Skills, Tools, LLM Providers, or Speech Providers.
- Improve documentation, localization, accessibility, tests, or Android code.
- Submit focused pull requests with a clear problem statement.

## Development workflow

1. Read [`docs/README.md`](docs/README.md) and the relevant product or
   architecture document.
2. Build from the native Android project under `android/`.
3. Keep changes focused and do not commit credentials, SDK paths, APKs, build
   output, or private user data.
4. Add or update tests when behavior changes.
5. Run the smallest relevant checks described in
   [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).
6. Update user-facing and architecture documentation in the same pull request.

## Provider requests

For a new LLM or Speech Provider, open an issue describing:

- the official API documentation;
- authentication and endpoint format;
- OpenAI compatibility, streaming, and Tool Call support where relevant;
- Android/network restrictions and expected error behavior;
- whether the service requires a paid account or regional availability.

Provider implementations should keep credentials in Android Keystore-backed
storage, expose explicit configuration, avoid silent fallbacks, and include
tests.

## Pull requests

Explain the user-visible outcome, implementation boundaries, and validation
performed. Maintainers may ask for a smaller scope or architecture updates
before merging.
