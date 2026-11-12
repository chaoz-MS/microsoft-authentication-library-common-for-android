# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.

import io
from pathlib import Path
import tempfile
import unittest
import zipfile

import verify_legacy_ext as verifier


class VerifyLegacyExtTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.common_classes = [
            verifier.COMMON + name + ".class" for name in (
                "components/AndroidPlatformComponentsFactory",
                "internal/platform/AndroidPlatformUtil",
                "internal/ui/AndroidAuthorizationStrategyFactory",
                "internal/providers/oauth2/WebViewAuthorizationFragment",
            )
        ]
        self.legacy_classes = [
            verifier.LEGACY + name + ".class" for name in (
                "components/AndroidPlatformComponentsFactoryExt",
                "internal/providers/oauth2/WebViewAuthorizationFragment",
            )
        ]

    def _aar(self, name, classes, resources, component, asset, rules=""):
        path = Path(self.temporary.name) / (name + ".aar")
        jar_bytes = io.BytesIO()
        with zipfile.ZipFile(jar_bytes, "w") as jar:
            for entry in classes:
                jar.writestr(entry, b"")
        with zipfile.ZipFile(path, "w") as aar:
            aar.writestr("classes.jar", jar_bytes.getvalue())
            aar.writestr("assets/", "")
            aar.writestr("R.txt", resources)
            aar.writestr(
                "AndroidManifest.xml",
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                f'<application><activity android:name="{component}"/></application></manifest>',
            )
            aar.writestr("assets/" + asset, "")
            aar.writestr("proguard.txt", rules)
        return path

    def _verify(self, *, common_classes=None, legacy_classes=None,
                resources="int layout edge_oneauth_legacy_login 0x0",
                component="com.microsoft.identity.common.edgewebview.Login",
                asset="edge_oneauth_legacy_js-bridge.js", rules=""):
        common = self._aar(
            "common", self.common_classes if common_classes is None else common_classes,
            "int layout login 0x0", "com.microsoft.identity.common.Login", "js-bridge.js",
        )
        legacy = self._aar(
            "legacy", self.legacy_classes if legacy_classes is None else legacy_classes,
            resources, component, asset, rules,
        )
        return verifier.verify(common, legacy)

    def test_isolated_legacy_ext(self):
        self.assertEqual((4, 2, 1), self._verify())

    def test_rejects_split_base_without_native_ui(self):
        with self.assertRaisesRegex(ValueError, "both shared runtime and native UI"):
            self._verify(common_classes=self.common_classes[:-1])

    def test_rejects_custom_native_common(self):
        for namespace in (verifier.LEGACY, verifier.COMMON + "systemwebview/"):
            with self.subTest(namespace=namespace), self.assertRaisesRegex(
                ValueError, "experimental namespace"
            ):
                self._verify(common_classes=self.common_classes + [namespace + "Extra.class"])

    def test_rejects_empty_legacy(self):
        with self.assertRaisesRegex(ValueError, "isolated UI and factory"):
            self._verify(legacy_classes=[])

    def test_rejects_shared_runtime_in_legacy(self):
        with self.assertRaisesRegex(ValueError, "shared runtime classes"):
            self._verify(legacy_classes=self.legacy_classes + self.common_classes[:1])

    def test_rejects_unprefixed_resource(self):
        with self.assertRaisesRegex(ValueError, "dedicated prefix"):
            self._verify(resources="int layout login 0x0")

    def test_rejects_empty_resource_inventory(self):
        with self.assertRaisesRegex(ValueError, "dedicated prefix"):
            self._verify(resources="")

    def test_rejects_original_component(self):
        with self.assertRaisesRegex(ValueError, "isolated namespace"):
            self._verify(component="com.microsoft.identity.common.Login")

    def test_rejects_stock_asset_override(self):
        with self.assertRaisesRegex(ValueError, "must not override"):
            self._verify(asset="js-bridge.js")

    def test_rejects_unscoped_consumer_rules(self):
        with self.assertRaisesRegex(ValueError, "shared common types"):
            self._verify(rules="-keep class com.microsoft.identity.common.** { *; }")

    def test_accepts_scoped_consumer_rules(self):
        self._verify(rules="-keep class com.microsoft.identity.common.edgewebview.** { *; }")


if __name__ == "__main__":
    unittest.main()
