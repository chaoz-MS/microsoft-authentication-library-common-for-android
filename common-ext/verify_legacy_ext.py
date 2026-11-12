# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.

"""Check that the legacy UI AAR cannot replace stock common runtime or UI."""

import argparse
import io
from pathlib import Path
import re
import zipfile
import xml.etree.ElementTree as ET

COMMON = "com/microsoft/identity/common/"
LEGACY = COMMON + "edgewebview/"
RESOURCE_PREFIX = "edge_oneauth_legacy_"
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"


def _classes(archive):
    with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
        return {name for name in classes.namelist() if name.endswith(".class")}


def _resources(archive):
    return {
        tuple(line.split()[1:3])
        for line in archive.read("R.txt").decode("utf-8").splitlines()
        if line.strip()
    }


def _components(archive):
    application = ET.fromstring(archive.read("AndroidManifest.xml")).find("application")
    if application is None:
        return set()
    return {
        element.attrib[ANDROID_NAME]
        for element in application
        if element.tag in ("activity", "activity-alias", "service", "receiver", "provider")
    }


def verify(common_aar, legacy_aar):
    with zipfile.ZipFile(common_aar) as common, zipfile.ZipFile(legacy_aar) as legacy:
        common_classes = _classes(common)
        legacy_classes = _classes(legacy)
        required_common = {
            COMMON + "components/AndroidPlatformComponentsFactory.class",
            COMMON + "internal/platform/AndroidPlatformUtil.class",
            COMMON + "internal/ui/AndroidAuthorizationStrategyFactory.class",
            COMMON + "internal/providers/oauth2/WebViewAuthorizationFragment.class",
        }
        if not required_common.issubset(common_classes):
            raise ValueError("Stock common must contain both shared runtime and native UI")
        if any(name.startswith((LEGACY, COMMON + "systemwebview/")) for name in common_classes):
            raise ValueError("Stock common must not contain either experimental namespace")
        required_legacy = {
            LEGACY + "components/AndroidPlatformComponentsFactoryExt.class",
            LEGACY + "internal/providers/oauth2/WebViewAuthorizationFragment.class",
        }
        if not required_legacy.issubset(legacy_classes):
            raise ValueError("Legacy ext must contain its isolated UI and factory")
        if any(not name.startswith(LEGACY) for name in legacy_classes):
            raise ValueError("Legacy ext must not own shared runtime classes")
        if common_classes & legacy_classes:
            raise ValueError("Duplicate classes across stock common and legacy ext")

        common_resources = _resources(common)
        legacy_resources = _resources(legacy)
        if not legacy_resources or any(
            not name.startswith(RESOURCE_PREFIX) for _, name in legacy_resources
        ):
            raise ValueError("Every legacy resource must have the dedicated prefix")
        if common_resources & legacy_resources:
            raise ValueError("Legacy ext must not overlay stock common resources")
        common_components = _components(common)
        legacy_components = _components(legacy)
        if not legacy_components or any(
            not name.startswith(LEGACY.replace("/", ".")) for name in legacy_components
        ):
            raise ValueError("Every legacy component must use the isolated namespace")
        if common_components & legacy_components:
            raise ValueError("Duplicate manifest components")
        common_assets = {
            entry.filename for entry in common.infolist()
            if entry.filename.startswith("assets/") and not entry.is_dir()
        }
        legacy_assets = {
            entry.filename for entry in legacy.infolist()
            if entry.filename.startswith("assets/") and not entry.is_dir()
        }
        if common_assets & legacy_assets or any(
            not Path(name).name.startswith(RESOURCE_PREFIX) for name in legacy_assets
        ):
            raise ValueError("Legacy assets must not override stock common assets")
        if re.search(
            r"com\.microsoft\.identity\.common\.(?!edgewebview\.)",
            legacy.read("proguard.txt").decode("utf-8"),
        ):
            raise ValueError("Legacy consumer rules must not own shared common types")
    return len(common_classes), len(legacy_classes), len(legacy_resources)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--common", type=Path, required=True)
    parser.add_argument("--legacy", type=Path, required=True)
    options = parser.parse_args()
    common_count, legacy_count, resource_count = verify(options.common, options.legacy)
    print(
        f"Verified stock common ({common_count} classes) and isolated legacy ext "
        f"({legacy_count} classes, {resource_count} resources)."
    )
