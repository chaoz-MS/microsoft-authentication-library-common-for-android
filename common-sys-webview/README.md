# System WebView UI variant

This is an experimental, source-built companion to the existing Edge
`common-base` and `common-ext` artifacts. Consume all three from the same
ingestion revision and version. It is not a standalone authentication library,
and does not change the default provider.

The module compiles the existing UI sources in
`com.microsoft.identity.common.systemwebview`, without depending on the
`common-ext` binary. `common-base` remains the sole owner of shared factories,
global initialization, cache, storage, broker state, and `common4j` types.
The authentication layout has its own name and uses `android.webkit.WebView`;
unchanged resources remain shared with the legacy UI.
The consumer must merge this AAR as a resource overlay when both UI variants
are present. The verifier requires every shared legacy resource to be present
and byte-identical, so an overlay cannot silently select a divergent UI value.

`platform/` contains the unmodified system WebView passkey transport and its
JavaScript asset from upstream release 24.7.0, commit
`ec3caa452d2436bf5092f38f8509e61ca856668c`. The legacy fork's
`addJavascriptInterface` transport is deliberately excluded. System WebView
retains AndroidX's origin-bound message listener, main-frame validation, and
reply proxy. Its debug asset is packaged under `systemwebview/`, so it cannot
replace the legacy JavaScript asset. Review these platform files when updating
the upstream version; do not copy the legacy transport over them.

Build the aligned artifacts using the normal ingestion environment:

```sh
./gradlew :common:bundleDistReleaseAar :common-ext:bundleDistReleaseAar \
  :common-sys-webview:bundleDistReleaseAar
```

Check the compiled class ownership, manifest attributes, platform layout,
passkey transport, assets, and consumer rules:

```sh
./gradlew :common-sys-webview:verifySystemWebViewAar
```

The same check, including the verifier's negative tests, runs before publishing
the system artifact and as part of this module's `check` task.

To produce the new artifact's local Maven publication and source archive:

```sh
./gradlew :common-sys-webview:publishDistReleasePublicationToMavenLocal
```

Repository credentials belong in the user's Gradle configuration or the
documented environment variables, never in this checkout. Remote publication
and downstream ingestion require the normal package review and release process.

This module replaces downstream common UI extraction and namespace rewriting,
not OneAuth's provider routing, activity lifecycle, MAM, storage, or proxy
integration. Those remain the responsibility of the consuming Edge integration.
Before shipping a system-only provider, switch back to the standard common
artifact and remove this experimental module and the legacy split wiring.
