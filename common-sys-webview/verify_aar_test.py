# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.

import io
from pathlib import Path
import tempfile
import unittest
import zipfile

import verify_aar as verifier


def archive(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as destination:
        for name, value in entries.items():
            destination.writestr(name, value)
    return output.getvalue()


def manifest(prefix):
    return (
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
        '<application><activity android:name="'
        + prefix
        + 'internal.ui.ReturnActivity" android:exported="false"/>'
        '</application></manifest>'
    ).encode()


class VerifyAarTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = {
            'classes.jar': archive(
                {
                    verifier.COMMON
                    + 'components/AndroidPlatformComponentsFactory.class': b'base'
                }
            ),
            'AndroidManifest.xml': b'<manifest><application/></manifest>',
        }
        legacy_classes = {
            verifier.COMMON + verifier.FACTORY: b'factory',
            verifier.COMMON
            + 'internal/providers/oauth2/PasskeyWebListener.class': b'legacy',
            verifier.COMMON
            + 'internal/providers/oauth2/CredentialManagerHandler.class': b'legacy',
        }
        self.system_classes = {
            verifier.SYSTEM + verifier.FACTORY: verifier.COMMON.encode()
            + b'components/AndroidPlatformComponentsFactory',
            verifier.SYSTEM
            + 'internal/providers/oauth2/PasskeyWebListener.class': b'androidx/webkit/WebViewCompat$WebMessageListener '
            b'androidx/webkit/JavaScriptReplyProxy',
            verifier.SYSTEM
            + 'internal/providers/oauth2/CredentialManagerHandler.class': b'(Ljava/lang/String;[BZLjava/lang/String;Z)V',
        }
        self.legacy = {
            'classes.jar': archive(legacy_classes),
            'AndroidManifest.xml': manifest(verifier.COMMON.replace('/', '.')),
            verifier.AUTH_LAYOUT: b'<EdgeOneAuthWebView/>',
        }
        self.system = {
            'AndroidManifest.xml': manifest(verifier.SYSTEM.replace('/', '.')),
            verifier.SYSTEM_LAYOUT: b'<WebView/>',
            'assets/systemwebview/js-bridge.js': b"addEventListener('message', onReply)",
            'proguard.txt': b'@android.webkit.JavascriptInterface <methods>;',
        }

    def verify(self):
        self.system['classes.jar'] = archive(self.system_classes)
        paths = []
        for name, entries in [
            ('base', self.base),
            ('legacy', self.legacy),
            ('system', self.system),
        ]:
            path = Path(self.directory.name) / f'{name}.aar'
            path.write_bytes(archive(entries))
            paths.append(path)
        return verifier.verify(*paths)

    def test_aligned_artifacts(self):
        self.assertEqual(self.verify()['system_classes'], 3)

    def test_duplicate_shared_class(self):
        self.system_classes[
            verifier.COMMON
            + 'components/AndroidPlatformComponentsFactory.class'
        ] = b'duplicate'
        with self.assertRaisesRegex(ValueError, 'Duplicate class ownership'):
            self.verify()

    def test_unrelocated_system_class(self):
        self.system_classes[verifier.COMMON + 'Unexpected.class'] = (
            b'unrelocated'
        )
        with self.assertRaisesRegex(ValueError, 'unrelocated class'):
            self.verify()

    def test_missing_ui_class(self):
        del self.system_classes[
            verifier.SYSTEM
            + 'internal/providers/oauth2/CredentialManagerHandler.class'
        ]
        with self.assertRaisesRegex(ValueError, 'Missing system UI class'):
            self.verify()

    def test_missing_shared_initialization(self):
        self.system_classes[verifier.SYSTEM + verifier.FACTORY] = (
            b'private initialization'
        )
        with self.assertRaisesRegex(ValueError, 'shared base factory'):
            self.verify()

    def test_legacy_javascript_transport(self):
        self.system_classes[
            verifier.SYSTEM
            + 'internal/providers/oauth2/WebViewJavaScriptBridge.class'
        ] = b'bridge'
        with self.assertRaisesRegex(ValueError, 'legacy JavaScript transport'):
            self.verify()

    def test_missing_origin_bound_listener(self):
        self.system_classes[
            verifier.SYSTEM
            + 'internal/providers/oauth2/PasskeyWebListener.class'
        ] = b'bridge'
        with self.assertRaisesRegex(
            ValueError, 'origin-bound AndroidX transport'
        ):
            self.verify()

    def test_incompatible_passkey_constructor(self):
        self.system_classes[
            verifier.SYSTEM
            + 'internal/providers/oauth2/CredentialManagerHandler.class'
        ] = b'default'
        with self.assertRaisesRegex(
            ValueError, 'explicit AndroidX constructor'
        ):
            self.verify()

    def test_legacy_layout_collision(self):
        self.system[verifier.AUTH_LAYOUT] = b'<WebView/>'
        with self.assertRaisesRegex(
            ValueError, 'override the legacy authentication layout'
        ):
            self.verify()

    def test_identical_shared_resource(self):
        name = 'res/values/values.xml'
        self.legacy[name] = self.system[name] = (
            b'<resources><string name="app_loading">Loading</string></resources>'
        )
        self.verify()

    def test_changed_shared_resource(self):
        name = 'res/values/values.xml'
        self.legacy[name] = b'<resources/>'
        self.system[name] = b'<resources><string name="extra">X</string></resources>'
        with self.assertRaisesRegex(ValueError, 'Shared UI resource differs'):
            self.verify()

    def test_missing_shared_resource(self):
        self.legacy['res/values/values.xml'] = b'<resources/>'
        with self.assertRaisesRegex(ValueError, 'Shared UI resource differs'):
            self.verify()

    def test_non_platform_webview(self):
        self.system[verifier.SYSTEM_LAYOUT] = b'<EdgeOneAuthWebView/>'
        with self.assertRaisesRegex(ValueError, 'platform WebView'):
            self.verify()

    def test_debug_asset_collision(self):
        self.system['assets/js-bridge.js'] = b'override'
        with self.assertRaisesRegex(
            ValueError, 'must not replace the legacy asset'
        ):
            self.verify()

    def test_changed_manifest_security_attribute(self):
        self.system['AndroidManifest.xml'] = self.system[
            'AndroidManifest.xml'
        ].replace(b'exported="false"', b'exported="true"')
        with self.assertRaisesRegex(ValueError, 'security attributes'):
            self.verify()

    def test_unrelocated_manifest_component(self):
        self.system['AndroidManifest.xml'] = self.system[
            'AndroidManifest.xml'
        ].replace(verifier.SYSTEM.replace('/', '.').encode(), b'com.example.')
        with self.assertRaisesRegex(ValueError, 'unrelocated component'):
            self.verify()

    def test_duplicate_manifest_component(self):
        self.system['AndroidManifest.xml'] = self.system[
            'AndroidManifest.xml'
        ].replace(
            b'</application>',
            b'<activity android:name="'
            + verifier.SYSTEM.replace('/', '.').encode()
            + b'internal.ui.ReturnActivity"/></application>',
        )
        with self.assertRaisesRegex(ValueError, 'Duplicate manifest component'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
