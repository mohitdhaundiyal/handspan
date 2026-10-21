# Contributing

## Branches

| Branch | Purpose | Changes arrive by |
|---|---|---|
| `main` | Released code. Every new version merged here is published as a GitHub release. | Pull request from `dev` only, merge commit |
| `dev` | Integration branch for the next release. | Pull request from a work branch, squash merge |
| `feature/…`, `fix/…`, `docs/…`, `release/…` | One change each. | Direct pushes |

Nobody pushes to `dev` or `main` directly, force-pushes them or deletes them; GitHub rejects it.
Every pull request needs the CI checks to pass, and only the maintainer
([@mohitdhaundiyal](https://github.com/mohitdhaundiyal)) merges.

## Making a change

```sh
git switch dev && git pull
git switch -c fix/short-description
# … commit …
git push -u origin fix/short-description
```

Open a pull request into `dev`. CI runs:

| Check | What it does |
|---|---|
| **Android** | Kotlin unit tests, Android lint, debug APK build |
| **macOS** | Swift tests, the Kotlin client against the Swift host over real TLS, universal app and DMG build |

## Releasing

Versions follow [semantic versioning](https://semver.org): `MAJOR.MINOR.PATCH`. Both apps share
one version and one build number.

1. On a branch into `dev`, bump the version:
   ```sh
   git switch -c release/1.1.0 dev
   scripts/version.sh bump 1.1.0    # sets Android and macOS versions, increments the build number
   git commit -am "Release 1.1.0" && git push -u origin release/1.1.0
   ```
   Merge that pull request into `dev`.
2. Open a pull request from `dev` into `main`. Besides the checks above, **Release version**
   verifies that it comes from `dev`, that the version has not been released and that the build
   number went up.
3. Merging it runs the **Release** workflow: it builds the signed APK and the DMG, tags
   `v1.1.0` and publishes the GitHub release with both files and their checksums. The download
   links in the README always point at the latest release.

A merge into `main` without a version bump publishes nothing.

### Signing

The release workflow signs the APK with the key in the `ANDROID_KEYSTORE_B64` and
`ANDROID_KEYSTORE_PASSWORD` repository secrets (key alias `handspan`). Android only installs an
update signed with the same key, so keep an offline backup of the keystore and its password.
