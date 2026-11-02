#!/usr/bin/env python3
"""Root-backed test setup for the administrator's Android test device.

Keeps PocketHelm's test permissions as a device baseline. Reapplies them after
installation because Android/MIUI can remove UID permissions and wakepath rules
when the instrumentation package is uninstalled. Never starts a model session.
"""
import argparse
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET

APP = "com.gmch.codexremote"
TEST = APP + ".test"
RUNNER = TEST + "/androidx.test.runner.AndroidJUnitRunner"
ROOT = Path(__file__).resolve().parents[2]
WAKE_URI = "content://com.lbe.security.miui.permmgr/StartActivityRuleList"
WAKE_DB = "/data/data/com.lbe.security.miui/databases/wake_path.db"


class Device:
    def __init__(self, serial=None):
        self.adb = ["adb"] + (["-s", serial] if serial else [])

    def run(self, *args, timeout=60):
        result = subprocess.run(self.adb + list(args), capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stdout.strip() + "\n" + result.stderr.strip())
        return result.stdout.strip()

    def root(self, command):
        # adb joins shell arguments. Quote the entire su command, including operators.
        return self.run("shell", "su -c " + shlex.quote(command))

    def installed(self, package):
        result = subprocess.run(self.adb + ["shell", "pm", "path", package], capture_output=True, text=True, timeout=30)
        if result.returncode and (result.stdout.strip() or result.stderr.strip()):
            raise RuntimeError(result.stdout.strip() + "\n" + result.stderr.strip())
        return result.returncode == 0 and result.stdout.startswith("package:")

    def setup(self):
        if "uid=0(" not in self.root("id"):
            raise RuntimeError("This test device requires the configured root access")
        if self.run("shell", "am", "get-current-user") != "0":
            raise RuntimeError("This setup supports the administrator's owner-user test device")
        if not self.installed(APP):
            raise RuntimeError("Install PocketHelm before configuring test permissions")
        for package in (APP, TEST):
            if not self.installed(package):
                continue
            if self.run("shell", "getprop", "ro.miui.ui.version.name"):
                for op in ("10021", "10032"):
                    for _ in range(3):
                        self.root(f"cmd appops set {package} {op} allow")
                        actual = self.root(f"cmd appops get {package} {op}")
                        if ": allow" in actual:
                            break
                        time.sleep(0.3)
                    else:
                        raise RuntimeError(f"Could not verify {package} AppOp {op}: {actual}")
        # CAMERA is PocketHelm's only dangerous permission (QR pairing). Grant both
        # runtime permission and the UID/package AppOps, which MIUI tracks separately.
        self.root(f"pm grant --user 0 {APP} android.permission.CAMERA")
        self.root(f"cmd appops set --uid {APP} CAMERA allow")
        self.root(f"cmd appops set {APP} CAMERA allow")
        permissions = self.run("shell", "dumpsys", "package", APP)
        if "android.permission.CAMERA: granted=true" not in permissions:
            raise RuntimeError("Camera runtime permission was not granted")
        if self.run("shell", "getprop", "ro.miui.ui.version.name"):
            # The native provider inserts package-pair rules and refreshes system_server.
            # Its unique pair index makes repeats harmless. Do not edit the live DB.
            for caller, callee in ((APP, TEST), (TEST, APP)):
                result = self.root(f"content insert --user 0 --uri {WAKE_URI} "
                                   f"--bind callerPkgName:s:{caller} --bind calleePkgName:s:{callee} "
                                   "--bind userSettings:i:0")
                if "Error" in result or "Exception" in result:
                    raise RuntimeError("MIUI rejected the test launch rule")
            raw = subprocess.check_output(self.adb + ["exec-out", "su -c " + shlex.quote("cat " + WAKE_DB)], timeout=30)
            with sqlite3.connect(":memory:") as db:
                db.deserialize(raw)
                for caller, callee in ((APP, TEST), (TEST, APP)):
                    rule = db.execute("SELECT userSettings FROM StartActivityRuleList "
                                      "WHERE callerPkgName=? AND calleePkgName=?", (caller, callee)).fetchone()
                    if rule != (0,):
                        raise RuntimeError("Could not verify the MIUI test launch rule")
        print("Verified root, camera, background launch and test-package launch permissions", flush=True)

    def install(self):
        apks = (ROOT / "android/app/build/outputs/apk/release/app-release.apk",
                ROOT / "android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk")
        if not all(path.is_file() for path in apks):
            raise RuntimeError("Build the signed release and release androidTest APKs first")
        for index, apk in enumerate(apks):
            remote = f"/data/local/tmp/pockethelm-device-test-{index}.apk"
            try:
                self.run("push", str(apk), remote)
                if "Success" not in self.root("pm install -r -g -t " + remote):
                    raise RuntimeError("Root package installation failed")
            finally:
                self.root("rm -f " + remote)

    def allow_test_dialog(self):
        """Fallback for an OEM update: handle only our exact launch-confirmation dialog."""
        activities = self.run("shell", "dumpsys", "activity", "activities")
        if not re.search(r"(?:topResumedActivity|mResumedActivity|ResumedActivity):?[^\n]*"
                         r"com\.miui\.securitycenter/com\.miui\.wakepath\.ui\.ConfirmStartActivity", activities):
            return False
        path = "/data/local/tmp/pockethelm-test-permission.xml"
        try:
            self.root("uiautomator dump " + path)
            raw = self.root("cat " + path)
            tree = ET.fromstring(raw)
            text = " ".join(node.get("text", "") for node in tree.iter("node"))
            if APP not in text:
                raise RuntimeError("A launch dialog outside the PocketHelm test scope appeared")
            for node in tree.iter("node"):
                if node.get("text") not in ("始终允许", "允许", "Always allow", "Allow"):
                    continue
                bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if bounds:
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    self.root(f"input tap {(x1+x2)//2} {(y1+y2)//2}")
                    print("Handled PocketHelm's OEM test launch confirmation using root", flush=True)
                    return True
            raise RuntimeError("Could not locate the OEM launch permission action")
        finally:
            self.root("rm -f " + path)

    def test(self, classes, output, timeout):
        if not self.installed(TEST):
            raise RuntimeError("Install the release androidTest APK first, or use --install")
        output.parent.mkdir(parents=True, exist_ok=True)
        dialogs = 0
        with output.open("w") as log:
            process = subprocess.Popen(self.adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", classes, RUNNER],
                                       stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + timeout
            try:
                while process.poll() is None:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Instrumentation timed out; see the private test log")
                    dialogs += self.allow_test_dialog()
                    time.sleep(1)
                if process.returncode:
                    raise RuntimeError("Instrumentation transport failed")
            finally:
                if process.poll() is None:
                    self.root(f"am force-stop {APP}")
                    self.root(f"am force-stop {TEST}")
                    process.kill()
                    process.wait(timeout=10)
        result = output.read_text()
        passed = re.search(r"OK \((\d+) tests?\)", result)
        if not passed or "FAILURES!!!" in result or "INSTRUMENTATION_FAILED" in result:
            raise RuntimeError("Device tests failed; see " + str(output))
        print(f"PASS: {passed.group(1)} device tests; OEM confirmation dialogs handled: {dialogs}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Administrator-configured adb device serial")
    parser.add_argument("--install", action="store_true", help="Root-install the existing signed release/test APKs")
    parser.add_argument("--class", dest="classes", help="Instrumentation classes or Class#method, comma-separated")
    parser.add_argument("--out", type=Path, default=Path.home() / ".local/state/pockethelm/device-test/instrument.txt")
    parser.add_argument("--timeout", type=int, default=360)
    args = parser.parse_args()
    device = Device(args.serial)
    try:
        if args.install:
            device.install()
        device.setup()
        if args.classes:
            device.test(args.classes, args.out, args.timeout)
    finally:
        if args.classes and device.installed(TEST):
            device.root(f"pm uninstall {TEST}")
            # The OEM removes pair rules at uninstall; retain the baseline and reapply
            # after the next installation as well. Do not restore background ops to ignore.
            device.setup()


if __name__ == "__main__":
    main()
