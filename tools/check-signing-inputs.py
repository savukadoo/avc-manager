#!/usr/bin/env python3
"""Fail closed for release identities; never print a password or create a key."""
import os
from pathlib import Path
import stat
import sys

if len(sys.argv)!=3:raise SystemExit("Pass the release key and password file.")
root = Path(__file__).resolve().parents[1]
for argument in sys.argv[1:]:
    candidate = Path(argument).resolve(strict=True)
    if root == candidate or root in candidate.parents:
        raise SystemExit("Release signing material must be outside the checkout.")
    metadata = candidate.stat()
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid():
        raise SystemExit("Release signing files must be regular files owned by this user.")
    if metadata.st_mode & 0o077:
        raise SystemExit("Release signing files must use private permissions (0600).")
