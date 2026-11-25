#!/usr/bin/env python3
"""Upload local media over HTTPS after pinning the certificate read on the CC3.

No pairing code, HTTP request or media byte is sent before the pin comparison.
The self-signed certificate is authenticated by its out-of-band SHA-256 pin,
not by a browser exception or an unverified trust-on-first-use decision.
"""
import argparse
import getpass
import hashlib
import hmac
import http.client
import ipaddress
import json
from pathlib import Path
import re
import ssl
from urllib.parse import quote, urlsplit


class PinnedConnection(http.client.HTTPSConnection):
    def __init__(self, host, port, fingerprint, timeout=30):
        expected = fingerprint.replace(":", "").replace(" ", "").lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ValueError("Read the complete SHA-256 certificate fingerprint on the CC3.")
        self.expected = expected
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        # A private self-signed certificate is verified by the exact pin below.
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        super().__init__(host, port, timeout=timeout, context=context)

    def connect(self):
        super().connect()
        actual = hashlib.sha256(self.sock.getpeercert(binary_form=True)).hexdigest()
        if not hmac.compare_digest(actual, self.expected):
            self.close()
            raise ssl.SSLError("Certificate fingerprint mismatch. No request or pairing code was sent.")


class Uploader:
    def __init__(self, url, fingerprint):
        parsed = urlsplit(url)
        if parsed.scheme != "https" or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
            raise ValueError("Use the exact HTTPS base address shown on the CC3.")
        address = ipaddress.ip_address(parsed.hostname or "")
        if address.version != 4 or not (address.is_private or address.is_loopback):
            raise ValueError("Choose the private local IPv4 address shown on the CC3.")
        self.host, self.port, self.fingerprint = str(address), parsed.port or 443, fingerprint
        self.authority = f"{self.host}:{self.port}"
        self.origin = f"https://{self.authority}"
        self.cookie = self.csrf = ""

    def request(self, path, body, length):
        connection = PinnedConnection(self.host, self.port, self.fingerprint)
        try:
            # Explicit connect verifies the certificate before even constructing a request.
            connection.connect()
            headers = {"Host": self.authority, "Origin": self.origin, "X-CC3-Upload": "1",
                       "Content-Length": str(length), "Content-Type": "application/octet-stream"}
            if self.cookie:
                headers.update({"Cookie": self.cookie, "X-CC3-CSRF": self.csrf})
            connection.request("POST", path, body, headers)
            response = connection.getresponse()
            data = response.read(1048577)
            if len(data) > 1048576:
                raise ValueError("Response exceeded the upload API limit.")
            result = json.loads(data)
            if response.status not in (200, 201):
                raise ValueError(result.get("error", f"Upload returned HTTP {response.status}"))
            if path == "/api/pair":
                cookie = response.getheader("Set-Cookie", "").split(";", 1)[0]
                csrf = result.get("csrf", "")
                if not re.fullmatch(r"cc3media=[0-9a-f]{64}", cookie) or not re.fullmatch(r"[0-9a-f]{64}", csrf):
                    raise ValueError("Invalid upload session response.")
                self.cookie, self.csrf = cookie, csrf
            return result
        finally:
            connection.close()

    def pair(self, pin):
        if not re.fullmatch(r"[0-9]{8}", pin):
            raise ValueError("Enter the eight-digit pairing code displayed on the CC3.")
        return self.request("/api/pair", pin.encode("ascii"), 8)

    def upload(self, filename):
        file = Path(filename)
        length = file.stat().st_size
        if not file.is_file() or not 0 < length <= 16 * 1024**3:
            raise ValueError("Choose a non-empty file no larger than 16 GiB.")
        with file.open("rb") as body:
            return self.request("/api/upload?name=" + quote(file.name, safe=""), body, length)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="HTTPS address displayed on CC3")
    parser.add_argument("--fingerprint", required=True, help="SHA-256 pin read directly on CC3")
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()
    try:
        uploader = Uploader(args.url, args.fingerprint)
        uploader.pair(getpass.getpass("CC3 pairing code: "))
        for filename in args.files:
            result = uploader.upload(filename)
            print("Saved:", result["name"], f"({result['bytes']} bytes)")
    except (OSError, ValueError, http.client.HTTPException) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
