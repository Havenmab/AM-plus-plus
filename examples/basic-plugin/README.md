# Independent plugin example

Copy this directory outside the AM++ checkout. Copy the exported SDK JAR to
`lib/ampp-plugin-api-v1.jar`. With JDK 17+, Gradle 9.7.1 and Android SDK 37:

```text
gradle pluginZip -PandroidSdk=/absolute/android/sdk
```

Alternatively point `-PamppSdk=/absolute/ampp-plugin-api-v1.jar` at the SDK.
No AM++ source project dependency is used. Output: `build/dist/basic-plugin.zip`.
Import in AM++ settings → 插件, enable, then restart the host.

This example observes the platform Activity lifecycle. Its observation does not
write the invocation arguments or result. A plugin implementing host-specific
features must locate and maintain its own targets using the supplied host loader.
