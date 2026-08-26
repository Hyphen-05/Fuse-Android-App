# Releases

## How a release happens

`.github/workflows/release.yml` cuts one automatically on **every push to `main`**, skipping pushes
that only touch `docs/**`, markdown or `LICENSE` so that writing up a finding does not ship an APK
identical to the last one. It runs the unit tests first and stops if they fail — a release that fails
its own tests is worse than no release.

Two other ways in, both still supported:

- **Push a tag `v*`** — releases under exactly that tag, which is how `v1.0` was cut.
- **`workflow_dispatch`** — a release by hand from the Actions tab, without needing an empty commit.

## Where the version number comes from

A tag push releases under its own tag. A `main` push builds one as `v<versionName>.<run_number>` —
`versionName` read out of `app/build.gradle.kts` (currently `1.0`), the run number supplied by
GitHub. So merges produce `v1.0.41`, `v1.0.42` and so on: unique and ordered without anyone having to
remember to bump anything.

`versionCode` in `app/build.gradle.kts` is **still 1 and is not touched by any of this**. It has never
been bumped. Android uses it to decide what counts as an upgrade, so if these APKs ever need to
upgrade each other properly rather than being installed over the top, that is the thing to wire to the
run number.

## The signing problem, which is real and predates all of this

CI has no release signing key, so releases ship the **debug-signed** build. That part is deliberate
and documented in the workflow. The problem is what the workflow used to do about it: it ran
`keytool -genkeypair` on every run, which generates **a brand new certificate each time**.

Android refuses to install an APK over an existing one signed by a different certificate. So every
release built this way must be **uninstalled before the next one can be installed** — which wipes app
data: scenes, paired devices, calibration, every toggle. That is almost certainly what has been
forcing the clean installs noted in the handoffs, and it is not something the phone or the app is
doing wrong.

**The fix is one repository secret.** Generate a keystore once, base64 it, and store it as
`RELEASE_KEYSTORE_BASE64`; the workflow uses it when present and falls back to the throwaway with a
build warning when it is not:

```bash
keytool -genkeypair -v -keystore fuse-release.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
base64 -w0 fuse-release.keystore > fuse-release.keystore.b64
```

Then paste that file's contents into Settings > Secrets and variables > Actions > New repository
secret, named `RELEASE_KEYSTORE_BASE64`. **Keep the `.keystore` file somewhere safe and out of the
repo** — losing it means the next release cannot upgrade the ones before it either, and the cycle
starts again.

One transition cost: the first release signed with the new stable key still will not install over an
APK signed by an old throwaway one. That uninstall is the last one needed.

## Caveats

- The workflow file has to be **on `main`** before any of this runs. GitHub reads the workflow from
  the commit being pushed, so the merge that first brings this file to `main` will itself cut a
  release.
- `paths-ignore` is evaluated for tag pushes too, so a tag placed on a docs-only commit may be
  skipped. Use `workflow_dispatch` if that ever comes up.
- Releases are **public**, on a public repo, and carry an installable APK. Every merge to `main`
  publishes one.
