# zdtun provenance and local patches

Base: `emanuele-f/zdtun` commit `4a53fbbbfc16ed012af676a871dd989f3583a5e6` (2026-11-10). The root README records the base import. LGPL-3.0-or-later and the original notices remain with the source.

CC3 adaptations:

- A rejected socket-open/protect callback prevents forwarding an unprotected Android VPN socket.
- Invalid initialization callbacks release the newly allocated tunnel.
- UDP debug logging no longer creates a dead store in release builds.
- Linux example helpers use bounded interface names and reentrant route tokenization.

The last helpers are compiled into the dependency but are not used by the Manager JNI integration. `third-party-lock.json` records exact local file hashes; it is not an upstream signature. Review changes before refreshing it. `tools/sbom.py` emits a CycloneDX software inventory including the bundled uthash header. The corresponding native source is retained for redistribution.
