# Copyright (c) Microsoft Corporation. All rights reserved.
# Licensed under the MIT License.

"""Check the aligned Edge base, legacy UI, and system UI AARs."""

import argparse
import io
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile


COMMON = 'com/microsoft/identity/common/'
SYSTEM = COMMON + 'systemwebview/'
ANDROID_NAME = '{http://schemas.android.com/apk/res/android}name'
COMPONENTS = ('activity', 'activity-alias', 'service', 'receiver', 'provider')
LEGACY_BRIDGE = COMMON + 'internal/providers/oauth2/WebViewJavaScriptBridge'
FACTORY = 'components/AndroidPlatformComponentsFactoryExt.class'
AUTH_LAYOUT = 'res/layout/common_activity_authentication.xml'
SYSTEM_LAYOUT = 'res/layout/system_common_activity_authentication.xml'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_archive(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate ZIP entries')
        return {name: archive.read(name) for name in names}


def read_aar(path):
    archive = read_archive(Path(path).read_bytes())
    classes = {
        name: data
        for name, data in read_archive(archive['classes.jar']).items()
        if name.endswith('.class')
    }
    return archive, classes


def components(manifest):
    root = ET.fromstring(manifest)
    result = {}
    for kind in COMPONENTS:
        for node in root.findall(f'./application/{kind}'):
            name = node.attrib[ANDROID_NAME]
            require(name not in result, 'Duplicate manifest component')
            result[name] = (node.tag, dict(node.attrib))
    return result


def verify(base_path, legacy_path, system_path):
    base, base_classes = read_aar(base_path)
    legacy, legacy_classes = read_aar(legacy_path)
    system, system_classes = read_aar(system_path)
    class_sets = [set(base_classes), set(legacy_classes), set(system_classes)]
    for left, right in ((0, 1), (0, 2), (1, 2)):
        require(
            not class_sets[left] & class_sets[right],
            'Duplicate class ownership between AARs',
        )
    require(system_classes, 'System UI AAR has no classes')
    require(
        all(name.startswith(SYSTEM) for name in system_classes),
        'System UI defines a shared or unrelocated class',
    )
    require(
        COMMON + 'components/AndroidPlatformComponentsFactory.class'
        in base_classes,
        'Missing shared platform factory',
    )
    require(SYSTEM + FACTORY in system_classes, 'Missing system UI factory')
    require(
        COMMON.encode() + b'components/AndroidPlatformComponentsFactory'
        in system_classes[SYSTEM + FACTORY],
        'System factory must call the shared base factory',
    )
    for name in legacy_classes:
        if '$' not in name and not name.startswith(LEGACY_BRIDGE):
            expected = SYSTEM + name.removeprefix(COMMON)
            require(
                expected in system_classes,
                f'Missing system UI class: {expected}',
            )
    require(
        not any('WebViewJavaScriptBridge' in name for name in system_classes),
        'System UI must not package the legacy JavaScript transport',
    )
    listener = system_classes[
        SYSTEM + 'internal/providers/oauth2/PasskeyWebListener.class'
    ]
    require(
        b'androidx/webkit/WebViewCompat$WebMessageListener' in listener,
        'System passkeys must use the origin-bound AndroidX transport',
    )
    require(
        b'androidx/webkit/JavaScriptReplyProxy' in listener,
        'System passkeys must preserve the frame-bound reply proxy',
    )
    handler = system_classes[
        SYSTEM + 'internal/providers/oauth2/CredentialManagerHandler.class'
    ]
    require(
        b'(Ljava/lang/String;[BZLjava/lang/String;Z)V' in handler,
        'Passkey creation must use the explicit AndroidX constructor',
    )
    require(
        AUTH_LAYOUT in legacy and AUTH_LAYOUT not in system,
        'System UI must not override the legacy authentication layout',
    )
    require(
        SYSTEM_LAYOUT in system, 'Missing isolated system authentication layout'
    )
    for name, data in legacy.items():
        if (name.startswith('res/') and not name.endswith('/')
                and name != AUTH_LAYOUT):
            require(
                name in system and system[name] == data,
                f'Shared UI resource differs or is missing: {name}',
            )
    require(
        b'<WebView' in system[SYSTEM_LAYOUT]
        and b'EdgeOneAuthWebView' not in system[SYSTEM_LAYOUT],
        'System authentication layout must use platform WebView',
    )
    require(
        'assets/systemwebview/js-bridge.js' in system
        and 'assets/js-bridge.js' not in system,
        'System passkey asset must not replace the legacy asset',
    )
    require(
        b"addEventListener('message', onReply)"
        in system['assets/systemwebview/js-bridge.js'],
        'System JavaScript asset must use the AndroidX reply channel',
    )
    require(
        b'@android.webkit.JavascriptInterface' in system['proguard.txt'],
        'Missing JavaScript interface consumer rules',
    )

    base_components = components(base['AndroidManifest.xml'])
    legacy_components = components(legacy['AndroidManifest.xml'])
    system_components = components(system['AndroidManifest.xml'])
    require(
        all(
            name.startswith(SYSTEM.replace('/', '.'))
            for name in system_components
        ),
        'System manifest contains an unrelocated component',
    )
    require(
        not set(base_components) & set(system_components),
        'Shared manifest components must be owned by common-base',
    )
    expected_components = {}
    for name, (kind, attributes) in legacy_components.items():
        relocated = name.replace(
            COMMON.replace('/', '.'), SYSTEM.replace('/', '.'), 1
        )
        expected_components[relocated] = (
            kind,
            {**attributes, ANDROID_NAME: relocated},
        )
    require(
        system_components == expected_components,
        'System manifest changed component ownership or security attributes',
    )
    return {
        'base_classes': len(base_classes),
        'legacy_classes': len(legacy_classes),
        'system_classes': len(system_classes),
        'system_components': len(system_components),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base')
    parser.add_argument('legacy')
    parser.add_argument('system')
    args = parser.parse_args()
    result = verify(args.base, args.legacy, args.system)
    print(
        'Aligned AAR verification passed: '
        + ', '.join(f'{name}={value}' for name, value in result.items())
    )


if __name__ == '__main__':
    main()
