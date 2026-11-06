"""Validate CI signing assets and generate manual App Store export settings."""

import argparse
import hashlib
import plistlib
import re
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID


def signing_settings(profile, identities, required_entitlements, bundle_id, now):
    expiration = profile["ExpirationDate"].replace(tzinfo=timezone.utc)
    if expiration <= now:
        raise ValueError("Provisioning profile has expired")
    entitlements = profile["Entitlements"]
    if (
        entitlements.get("get-task-allow", False)
        or "ProvisionedDevices" in profile
        or profile.get("ProvisionsAllDevices", False)
    ):
        raise ValueError("An App Store distribution provisioning profile is required")

    team = profile["TeamIdentifier"][0]
    if not re.fullmatch(r"[A-Z0-9]{10}", team):
        raise ValueError("Invalid team identifier")
    prefixes = profile["ApplicationIdentifierPrefix"]
    if entitlements.get("application-identifier") not in {
        f"{prefix}.{bundle_id}" for prefix in prefixes
    }:
        raise ValueError(f"Provisioning profile must explicitly match {bundle_id}")
    if entitlements.get("com.apple.developer.team-identifier") != team:
        raise ValueError("Provisioning profile team identifiers do not match")
    for key, value in required_entitlements.items():
        if entitlements.get(key) != value:
            raise ValueError(f"Provisioning profile does not grant entitlement: {key}")

    profile_uuid = str(UUID(profile["UUID"])).upper()
    installed = set(re.findall(
        r'\b([A-Fa-f0-9]{40}) "(?:Apple Distribution|iPhone Distribution):[^"\n]+"',
        identities,
    ))
    installed = {identity.upper() for identity in installed}
    certificates = {
        hashlib.sha1(certificate).hexdigest().upper()
        for certificate in profile["DeveloperCertificates"]
    }
    matching = sorted(installed & certificates)
    if not matching:
        raise ValueError("No valid distribution identity with a private key matches the profile")
    identity = matching[0]

    return {
        "method": "app-store-connect",
        "destination": "export",
        "signingStyle": "manual",
        "teamID": team,
        "signingCertificate": identity,
        "provisioningProfiles": {bundle_id: profile_uuid},
        "manageAppVersionAndBuildNumber": False,
        "uploadSymbols": True,
    }, {
        "IOS_TEAM_ID": team,
        "IOS_PROFILE_UUID": profile_uuid,
        "IOS_SIGNING_IDENTITY": identity,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--identities", type=Path, required=True)
    parser.add_argument("--entitlements", type=Path, required=True)
    parser.add_argument("--bundle-id", required=True)
    parser.add_argument("--export-options", type=Path, required=True)
    parser.add_argument("--upload-options", type=Path, required=True)
    parser.add_argument("--github-env", type=Path, required=True)
    args = parser.parse_args()
    try:
        options, environment = signing_settings(
            plistlib.loads(args.profile.read_bytes()),
            args.identities.read_text(),
            plistlib.loads(args.entitlements.read_bytes()),
            args.bundle_id,
            datetime.now(timezone.utc),
        )
    except (KeyError, IndexError, ValueError) as error:
        parser.exit(1, f"Invalid iOS signing assets: {error}\n")
    args.export_options.write_bytes(plistlib.dumps(options))
    args.upload_options.write_bytes(plistlib.dumps({
        **options,
        "destination": "upload",
        "manageAppVersionAndBuildNumber": True,
    }))
    with args.github_env.open("a") as output:
        for key, value in environment.items():
            output.write(f"{key}={value}\n")


if __name__ == "__main__":
    main()
