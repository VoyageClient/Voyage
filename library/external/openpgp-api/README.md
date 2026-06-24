# openpgp-api

The OpenPGP API client used to talk to OpenKeychain for PGP encrypt/decrypt.

- Source: https://github.com/open-keychain/openpgp-api (Apache-2.0, see `LICENSE`)
- Vendored as source (AIDL + Java) rather than a prebuilt artifact: it is not published on
  Maven Central, and building from source lets our toolchain regenerate the AIDL stubs and keeps
  everything diffable / reproducible offline (KitKat-safe; upstream minSdk is 9).

We omit three upstream files:

- `util/OpenPgpAppPreference.java` and `util/OpenPgpKeyPreference.java` — the only classes that
  reference the library's `res`/`R`; we build our own settings UI instead, so dropping them keeps
  this module resource-free.
- `AutocryptPeerUpdate.java` — Autocrypt peer state, unused here.
