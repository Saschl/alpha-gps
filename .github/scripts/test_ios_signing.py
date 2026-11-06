import hashlib
import plistlib
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from ios_signing import signing_settings


class SigningSettingsTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime.now(timezone.utc)
        self.bundle_id = "com.saschl.cameragps"
        self.certificate = b"test distribution certificate"
        self.identity = hashlib.sha1(self.certificate).hexdigest().upper()
        self.identities = f'  1) {self.identity} "Apple Distribution: Example (ABC1234567)"\n'
        self.entitlements = {
            "com.apple.developer.networking.HotspotConfiguration": True,
            "com.apple.developer.networking.wifi-info": True,
        }
        self.profile = {
            "UUID": "12345678-1234-1234-1234-123456789ABC",
            "ExpirationDate": (self.now + timedelta(days=30)).replace(tzinfo=None),
            "TeamIdentifier": ["ABC1234567"],
            "ApplicationIdentifierPrefix": ["OLD1234567"],
            "DeveloperCertificates": [self.certificate],
            "Entitlements": {
                **self.entitlements,
                "application-identifier": f"OLD1234567.{self.bundle_id}",
                "com.apple.developer.team-identifier": "ABC1234567",
                "get-task-allow": False,
            },
        }

    def settings(self):
        return signing_settings(
            self.profile, self.identities, self.entitlements, self.bundle_id, self.now,
        )

    def test_export_uses_matching_identity_and_preserves_version(self):
        options, environment = self.settings()
        self.assertEqual(options["method"], "app-store-connect")
        self.assertEqual(options["destination"], "export")
        self.assertEqual(options["signingStyle"], "manual")
        self.assertEqual(options["signingCertificate"], self.identity)
        self.assertEqual(options["teamID"], "ABC1234567")
        self.assertEqual(options["provisioningProfiles"][self.bundle_id], self.profile["UUID"])
        self.assertFalse(options["manageAppVersionAndBuildNumber"])
        self.assertEqual(environment["IOS_SIGNING_IDENTITY"], self.identity)

    def test_rejects_expired_profile(self):
        self.profile["ExpirationDate"] = self.now.replace(tzinfo=None)
        with self.assertRaisesRegex(ValueError, "expired"):
            self.settings()

    def test_rejects_development_adhoc_and_enterprise_profiles(self):
        for key, value in [("ProvisionedDevices", []), ("ProvisionsAllDevices", True)]:
            with self.subTest(key=key):
                self.profile[key] = value
                with self.assertRaisesRegex(ValueError, "App Store"):
                    self.settings()
                del self.profile[key]
        self.profile["Entitlements"]["get-task-allow"] = True
        with self.assertRaisesRegex(ValueError, "App Store"):
            self.settings()

    def test_rejects_wrong_app_and_wildcard_profiles(self):
        for app in ["OLD1234567.com.example.other", "OLD1234567.*"]:
            with self.subTest(app=app):
                self.profile["Entitlements"]["application-identifier"] = app
                with self.assertRaisesRegex(ValueError, "explicitly match"):
                    self.settings()

    def test_requires_wifi_capabilities(self):
        del self.profile["Entitlements"]["com.apple.developer.networking.wifi-info"]
        with self.assertRaisesRegex(ValueError, "wifi-info"):
            self.settings()

    def test_rejects_mismatched_or_missing_private_key(self):
        for identities in ["0 valid identities found", '1) ' + "A" * 40 + ' "Apple Distribution: Other"']:
            with self.subTest(identities=identities):
                self.identities = identities
                with self.assertRaisesRegex(ValueError, "private key matches"):
                    self.settings()

    def test_rejects_development_certificate(self):
        self.identities = self.identities.replace("Apple Distribution:", "Apple Development:")
        with self.assertRaisesRegex(ValueError, "distribution identity"):
            self.settings()

    def test_cli_writes_plist_and_appends_github_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "profile.plist").write_bytes(plistlib.dumps(self.profile))
            (root / "entitlements.plist").write_bytes(plistlib.dumps(self.entitlements))
            (root / "identities.txt").write_text(self.identities)
            (root / "env").write_text("EXISTING=value\n")
            subprocess.run([
                sys.executable, str(Path(__file__).with_name("ios_signing.py")),
                "--profile", str(root / "profile.plist"),
                "--identities", str(root / "identities.txt"),
                "--entitlements", str(root / "entitlements.plist"),
                "--bundle-id", self.bundle_id,
                "--export-options", str(root / "ExportOptions.plist"),
                "--upload-options", str(root / "UploadOptions.plist"),
                "--github-env", str(root / "env"),
            ], check=True)
            options = plistlib.loads((root / "ExportOptions.plist").read_bytes())
            self.assertEqual(options, self.settings()[0])
            upload = plistlib.loads((root / "UploadOptions.plist").read_bytes())
            self.assertEqual(upload, {
                **options,
                "destination": "upload",
                "manageAppVersionAndBuildNumber": True,
            })
            self.assertTrue((root / "env").read_text().startswith("EXISTING=value\n"))
            self.assertIn(f"IOS_PROFILE_UUID={self.profile['UUID']}\n", (root / "env").read_text())


if __name__ == "__main__":
    unittest.main()
