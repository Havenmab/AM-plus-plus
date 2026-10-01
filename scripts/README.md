# Device QA scripts

These scripts are optional device regressions used while adapting Apple Music 6.5.0. They are not part of the Gradle build.

## Requirements

- PowerShell 7 and ADB
- An unlocked device with Apple Music and the module installed
- `ANDROID_SERIAL` set, or the corresponding `-Serial` / `-Device` argument supplied
- Root access for scripts that record through `/data/local/tmp`
- Python with OpenCV (`cv2`) and NumPy for the image/video analyzers

Several liquid-glass checks use fixed coordinates or 1080 × 2376 regions from the reference phone. Review and adjust those values before running on another resolution. The tablet checks expect Apple Music to be open in landscape.

Example:

```powershell
$env:ANDROID_SERIAL = "your-device-serial"
.\scripts\verify-device-dual-pane.ps1
```

## Host profile verification (no device required)

`verify-host-profile.py` is a read-only DEX check of the exact host profile. It takes the original
XAPK (or a bare base APK) and verifies every class/method/field the AM++ version profile pins,
including the phone liquid-glass seams with `--glass` and the tablet iPad-style chrome host
resources with `--tablet-chrome` (6.5.3 only):

```powershell
python scripts\verify-host-profile.py "apple-music-6-5-3.xapk" --version-name 6.5.3 --version-code 1599 --glass --tablet-chrome
python scripts\verify-host-profile.py "Apple+Music_6.5.2_APKPure.xapk" --glass
```

The version tuple comes from `manifest.json` when the XAPK ships one; otherwise pass it explicitly.
A PASS means the static evidence behind a profile still holds for that package; it does not prove
runtime behaviour, runtime resource-ID resolution, container types or blur sampling.

`--tablet-chrome` extracts the base APK's `resources.arsc` and resolves every tablet resource name
against the type that owns it (`drawable`, `id`, `color`, `dimen`, `bool`), so a rename or a name
reused under another type fails the run — one `FAIL missing tablet chrome resource <type>/<name>`
per missing entry. It asserts only that the name/type pair is declared in the table: it does not
evaluate configuration qualifiers, read the resource value, or prove the on-device
`getIdentifier()`/`resourceId()` lookup resolves. Only the 6.5.3 (1599) profile registers the
group; 6.5.1 (1583) and 6.5.2 (1586) report the feature as DEGRADED and pin no resource names.

Besides the pinned classes, methods and fields, the script asserts the two seams whose names R8
reuses between builds: the direct catalog query (only one method may satisfy the
`(String, Map, Continuation)` shape, and it must be the verified name for that version) and the
obfuscated `androidx.lifecycle.LiveData` alias that `COMPOSE_OBSERVE_AS_STATE` accepts.

The 6.5.3 profile additionally asserts the two MediaApi hook points that only that build declares:
`MEDIA_API_CATALOG_REQUEST_EXECUTOR`, the six `execute()` targets `v8.D#d`, `v8.D#b`, `A5.l#d`,
`A5.l#c`, `Ic.n#d` and `Ic.n#e`, and `MEDIA_API_AMP_HTTP_INTERCEPTOR`, the `w8.d#a(Li.f)Gi.D`
interceptor together with the OkHttp surface it reads (`Li.f#e`, `Gi.A#a`/`#c`/`#b()`, `Gi.A$a#h`/
`#d`/`#b`, `Gi.t#a`/`#e()` and `Gi.D#a`/`#d`/`#f`). Apple Music 6.5.1 (1583) and 6.5.2 (1586)
deliberately declare no targets for either hook point: on those versions the module falls back to
the MediaApi storefront field plus the `MEDIA_API_LOCALIZATION` and `CONTENT_HTTP_LOCALIZATION`
seams and must report the extra sub-capabilities as DEGRADED, so the script carries no 6.5.1/6.5.2
assertions for them.
