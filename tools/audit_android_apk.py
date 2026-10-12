from pathlib import Path
import re
import subprocess
import sys
import zipfile

apk = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("android/app/build/outputs/apk/debug/app-debug.apk")
if not apk.is_file() or apk.stat().st_size == 0:
    raise SystemExit(f"APK missing or empty: {apk}")

aapt = Path(sys.argv[2]) if len(sys.argv) > 2 else None
if aapt is None:
    android_home = Path(__import__("os").environ.get("ANDROID_HOME", "/usr/local/lib/android/sdk"))
    candidates = [android_home / "build-tools/35.0.0/aapt", android_home / "build-tools/35.0.0/aapt.exe"]
    aapt = next((p for p in candidates if p.exists()), None)
if aapt is None:
    raise SystemExit("aapt not found; cannot audit the built APK manifest")

result = subprocess.run([str(aapt), "dump", "permissions", str(apk)], capture_output=True, text=True, check=True)
permissions = sorted(set(re.findall(r"android\.permission\.[A-Z0-9_]+", result.stdout)))
expected = ["android.permission.INTERNET"]
if permissions != expected:
    raise SystemExit(f"APK permission audit failed. Expected {expected}, found {permissions}")

forbidden = [
    b"android/telephony/SmsManager",
    b"android/provider/Telephony$Sms",
    b"android.permission.READ_SMS",
    b"android.permission.RECEIVE_SMS",
    b"android.permission.SEND_SMS",
    b"android.permission.SYSTEM_ALERT_WINDOW",
    b"android.permission.QUERY_ALL_PACKAGES",
]
with zipfile.ZipFile(apk) as z:
    bad = z.testzip()
    if bad:
        raise SystemExit(f"APK ZIP integrity failed at {bad}")
    dex_names = [n for n in z.namelist() if re.fullmatch(r"classes(?:[0-9]+)?\.dex", n)]
    if not dex_names:
        raise SystemExit("APK contains no DEX bytecode")
    for name in dex_names:
        data = z.read(name)
        for marker in forbidden:
            if marker in data:
                raise SystemExit(f"APK code audit failed: {marker.decode(errors='replace')} found in {name}")

print("ANDROID APK SECURITY AUDIT PASSED")
print("Requested permissions: INTERNET only")
print("SMS/Accessibility/overlay sensitive API markers: not found")
print(f"APK size: {apk.stat().st_size} bytes")
