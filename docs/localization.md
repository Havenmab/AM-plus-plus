# Localization

AM++ settings use the current Apple Music activity's primary configuration locale. Chinese (`zh`) locales use the existing Simplified Chinese text; English and other languages use English. Reopening the settings reads the locale again, including Android's per-app language setting. This does not change the language of catalog requests, song titles, lyrics, or user-entered names.

`core/src/main/kotlin/dev/amenhancer/module/i18n/ModuleText.kt` is the shared text catalog for settings, lyrics operations, and plugin management. It is JVM-only and travels with the module's code. Injected views cannot resolve module resource IDs through Apple Music's resource table, and embedded installs may not have a separately installed module package. The catalog therefore needs neither a module package context nor merged Android resources.

The settings host resolves text through `localizedText`, which supplies the active activity's locale. Core and plugin-runtime diagnostics use the current process default locale when the operation produces its result. The module's description shown outside Apple Music uses standard Android resources, with English in `values/` and Chinese in `values-zh/`.

To add or update text:

1. Add a named `ModuleText` entry with complete English and Chinese messages.
2. Use positional format arguments such as `%1$s` for names, IDs and counts. Keep the same arguments in both translations and keep whole sentences together so translators can change word order.
3. Use `localizedText(ModuleText.KEY, arguments...)` in the settings UI, or `ModuleText.KEY.text(arguments...)` for process diagnostics. Pass `locale = ...` when a caller has an explicit display locale.
4. Keep settings keys, enum storage values, package IDs, source identifiers, filenames, song names and imported content unchanged.

`ModuleTextTest` checks English fallback, Chinese locales, repeated locale selection, user data preservation, and matching format arguments for every catalog entry. Existing operation tests compare localized diagnostics instead of assuming a particular machine language.

No new language preference is stored by AM++, and plugins remain responsible for translating their own names, descriptions and settings pages.
