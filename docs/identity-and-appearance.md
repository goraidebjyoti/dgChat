# Identity, QR and appearance

## Sharing the APK

The APK contains code and assets, not a pre-generated user identity. On first launch, each fresh installation generates its own P-256 signing key in Android Keystore and its own X25519 Noise key. The 256-bit peer ID is the complete 32-byte SHA-256 hash over those two public keys, displayed as 64 hexadecimal characters. The full displayed fingerprint uses the complete 256-bit hash. The APK's developer signing certificate is separate from these per-installation user keys.

The version-2 identity QR contains the app/version, display name and both public keys. Import reconstructs the ID from the keys. Display names can match without IDs matching. Changing a display name can change the QR image while preserving the underlying ID and fingerprint. Public QR data does not contain private keys. The live camera scanner decodes frames locally using CameraX and ZXing, then imports the public keys. Version-1 identity codes are accepted and derive the new full ID from the same keys.

Two independent fresh installations, including two phones owned by the same person, normally get different IDs. Assuming independent random keys and the usual hash model, the approximate probability of any accidental 256-bit collision among one million installations is 4.32 × 10^-66. This is practical uniqueness, not a mathematical guarantee. No global registration server is required.

App restarts, updates with the same app ID/signing identity, theme changes and Quick Clear retain the user identity. Clearing all app data or a fresh reinstall creates a new identity. Debug and release installations have separate app IDs and identities. An imported QR identifies the keys; users should compare the full fingerprint before marking a person verified.

## Theme colours

Settings → Appearance contains Light/Dark/System mode, followed by Theme colour. Choose Teal, Ocean blue, Violet, Rose, Amber, Forest or Slate. Colour and brightness mode are independent and saved across restarts; installations with older preferences start with Teal. Buttons, containers, chips, cards and surfaces follow the selected palette. The identity QR remains black/white for optical readability.

All seven palettes supply both light and dark semantic colours. Local numeric checks passed 4.5:1 text contrast for the checked foreground/background roles; Android instrumentation additionally covers rendering, picker selection and saved preference migration. These Android tests still need execution in CI/on-device.

## Circular logo

The new vector mark is a teal circular badge with a chat bubble, connected peer nodes, mint orbit and amber markers. Separate foreground/background layers provide an adaptive launcher icon; a round-icon resource and Android 13+ monochrome layer are included, along with a white notification glyph. Android launchers control the outer mask; the logo artwork itself is circular. No raster asset dependency was added. Preview: [logo-preview.svg](logo-preview.svg).

Android implementation reference: https://developer.android.com/develop/ui/compose/system/icon_design_adaptive
Keystore reference: https://developer.android.com/privacy-and-security/keystore
