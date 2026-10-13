"""Read-only verification of the exact AM++ host profiles against a user-supplied package.

Every check below is an evidence claim taken from the adaptation record: the DEX class name, the
complete method signature (owner, static/instance descriptor and return type) and, where the
adaptation pinned one, the field name. No APK code is executed and no file is written.

Usage:
    python scripts/verify-host-profile.py apple-music-6-5-3.xapk \
        --version-name 6.5.3 --version-code 1599 --glass
"""
import argparse
import io
import json
import struct
import sys
import zipfile
import importlib.util
from pathlib import Path

_manifest_spec = importlib.util.spec_from_file_location('android_manifest',Path(__file__).with_name('android-package-manifest.py'))
_manifest_module = importlib.util.module_from_spec(_manifest_spec)
_manifest_spec.loader.exec_module(_manifest_module)


def _uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
    raise ValueError("Invalid ULEB128")


def dex_classes(data):
    """Return class descriptors, inherited interfaces, methods and fields."""
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a DEX file")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def u16(offset):
        return struct.unpack_from("<H", data, offset)[0]

    strings = []
    for i in range(u32(56)):
        _, offset = _uleb(data, u32(u32(60) + 4 * i))
        strings.append(data[offset:data.index(0, offset)].decode("utf8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    protos = []
    for i in range(u32(72)):
        base = u32(76) + i * 12
        parameters = u32(base + 8)
        params = "" if not parameters else "".join(
            types[u16(parameters + 4 + j * 2)] for j in range(u32(parameters))
        )
        protos.append("(" + params + ")" + types[u32(base + 4)])
    field_defs = []
    for i in range(u32(80)):
        _, type_idx, name_idx = struct.unpack_from("<HHI", data, u32(84) + i * 8)
        field_defs.append((strings[name_idx], types[type_idx]))
    method_defs = []
    for i in range(u32(88)):
        _, proto_idx, name_idx = struct.unpack_from("<HHI", data, u32(92) + i * 8)
        method_defs.append(strings[name_idx] + protos[proto_idx])
    classes = {}
    for i in range(u32(96)):
        base = u32(100) + i * 32
        class_name = types[u32(base)]
        superclass = u32(base + 8)
        interfaces_offset = u32(base + 12)
        interfaces = [] if not interfaces_offset else [
            types[u16(interfaces_offset + 4 + 2 * j)] for j in range(u32(interfaces_offset))
        ]
        offset = u32(base + 24)
        methods = set()
        fields = {}
        method_access = {}
        field_access = {}
        if offset:
            counts = []
            for _ in range(4):
                count, offset = _uleb(data, offset)
                counts.append(count)
            for section in counts[:2]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    flags, offset = _uleb(data, offset)
                    fields[field_defs[index][0]] = field_defs[index][1]
                    field_access[field_defs[index][0]] = flags
            for section in counts[2:]:
                index = 0
                for _ in range(section):
                    delta, offset = _uleb(data, offset)
                    index += delta
                    flags, offset = _uleb(data, offset)
                    _, offset = _uleb(data, offset)
                    methods.add(method_defs[index])
                    method_access[method_defs[index]] = flags
        classes[class_name] = {
            "super": types[superclass] if superclass != 0xFFFFFFFF else None,
            "interfaces": interfaces,
            "methods": methods,
            "fields": fields,
            "method_access": method_access,
            "field_access": field_access,
        }
    return classes


PROFILE_DIRECTORY = Path(__file__).resolve().parents[1] / "host-applemusic/src/main/resources/host-profiles"


def load_profiles():
    index = json.loads((PROFILE_DIRECTORY / "index.json").read_text(encoding="utf-8"))
    profiles = {}
    for filename in index["profiles"]:
        data = json.loads((PROFILE_DIRECTORY / filename).read_text(encoding="utf-8"))
        if data["productionEnabled"]:
            profiles[(data["versionName"], str(data["versionCode"]))] = data["verification"]
    return profiles


PROFILES = load_profiles()
CATALOG_QUERY_SHAPE = "(Ljava/lang/String;Ljava/util/Map;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"


def find_method(classes, owner, signature):
    current = owner
    while current in classes:
        if signature in classes[current]["methods"]:
            return current
        current = classes[current]["super"]
    return None


def verify_fragment_contracts(document, classes, failures):
    """Cover declared helper/binding/constructor dependencies as well as indexed hooks."""
    if document.get('family') != 'fragment-content':
        return 0
    primitive = {'void': 'V', 'int': 'I', 'long': 'J', 'float': 'F', 'boolean': 'Z',
                 'double': 'D', 'byte': 'B', 'short': 'S', 'char': 'C'}
    def descriptor(name):
        return primitive.get(name, name.replace('.', '/') if name.startswith('[')
                             else 'L' + name.replace('.', '/') + ';')
    sections = {name: document.get(name) for name in (
        'fragmentDualPane', 'fragmentLyricsRuntime', 'fragmentKaraokeWidth', 'fragmentTabletVideoHandoff')}
    sections['fragmentChrome.members'] = (document.get('fragmentChrome') or {}).get('members')
    main = sections.get('fragmentDualPane') or {}
    policy = main.get('songLayoutPolicy')
    if policy == '1606-column':
        sections['fragmentDualPane.songLayout'] = main.get('songLayout')
    elif policy != 'native' or 'songLayout' in main:
        failures.append('fragmentDualPane.songLayoutPolicy: select an explicit, consistent layout policy')
    for role in ('artwork', 'metadata', 'controls', 'progress', 'binding', 'restoration'):
        if not str(main.get('layoutReview', {}).get(role, '')).strip():
            failures.append(f'fragmentDualPane.layoutReview.{role}: missing comparison evidence')
    checks = 0
    for section, data in sections.items():
        if not isinstance(data, dict) or not data.get('methods') or not data.get('fields'):
            failures.append(f'{section}: missing complete member inventory')
            continue
        for group in ('methods', 'fields', 'constructors'):
            for role, contract in data.get(group, {}).items():
                checks += 1
                try:
                    if section == 'fragmentChrome.members' and group == 'methods' and role == 'activityTouch':
                        # This public framework method is absent from the application's DEX.
                        if contract != dict(owner='android.app.Activity', name='dispatchTouchEvent',
                                            parameters=['android.view.MotionEvent'], returns='boolean', static=False):
                            failures.append(f'{section}.{group}.{role}: invalid framework descriptor')
                        continue
                    native = classes.get(descriptor(contract['owner']), {})
                    if group == 'fields':
                        valid = (native.get('fields', {}).get(contract['name']) == descriptor(contract['type'])
                                 and not native.get('field_access', {}).get(contract['name'], 8) & 8)
                    else:
                        name = '<init>' if group == 'constructors' else contract['name']
                        returns = 'V' if group == 'constructors' else descriptor(contract['returns'])
                        signature = name + '(' + ''.join(map(descriptor, contract['parameters'])) + ')' + returns
                        flags = native.get('method_access', {}).get(signature)
                        forbidden = 0x40 | 0x1000 | (0 if contract.get('invocationOnly') else 0x400)
                        valid = (flags is not None and not flags & forbidden
                                 and bool(flags & 8) == contract.get('static', False))
                    if not valid:
                        failures.append(f'{section}.{group}.{role}: invalid exact descriptor {contract}')
                except (KeyError, TypeError, AttributeError) as error:
                    failures.append(f'{section}.{group}.{role}: incomplete descriptor ({error})')
    return checks


def verify_lyric_blur_contract(document, classes, failures):
    """Independent blur inventory for both host families; run only when the verifier is requested."""
    data = document.get('lyricBlur')
    if data is None and not document.get('productionEnabled'):
        return 0
    if not isinstance(data, dict) or data.get('contractVersion') != 1:
        failures.append('lyricBlur: missing versioned production contract')
        return 0
    adapter_roles = ('Commit', 'Snapshot', 'Map', 'Type', 'Id', 'Reader', 'Token', 'Document', 'Bind', 'Payload', 'Recycle')
    native_roles = ('nativeLine', 'nativeWord', 'nativeBackground', 'nativePronunciation', 'nativePronunciationBackground')
    word_roles = ('wordProgress', 'wordTail', 'wordLift', 'wordReturn')
    required_methods = {
        'createView', 'destroyView', 'resume', 'installDocument', 'readerCount', 'readerLine', 'lineGet', 'nativeLineId',
        'lineVectorSize', 'lineVectorGet', 'wordVectorSize', 'wordVectorGet', 'wordGet', 'wordLine', 'nativeWordId',
        'getAdapter', 'getScrollState', 'setScrollState', 'onTouchEvent', 'onInterceptTouchEvent', 'rowPosition',
        'rowHolder', 'registerChildListener', 'childAttach', 'childDetach', 'vmWord', 'vmLine', 'seek', 'seekItem',
        'nativeLineBegin', 'mediaBrowser', 'mediaPosition',
    } | {prefix + role for prefix in ('line', 'word') for role in adapter_roles} | set(native_roles) | {
        role + phase for role in word_roles for phase in ('Start', 'End')}
    required_fields = {
        'binding', 'recycler', 'activeAdapter', 'viewModel', 'holderView', 'holderId', 'holderType', 'wordLineId',
        'wordAnimator', 'wordTailAnimators', 'childListeners', 'words', 'backgroundWords', 'pronunciationWords',
        'pronunciationBackgroundWords', 'instrumentalPosition', 'wordInstrumentalPosition',
    } | set(native_roles) | {role + 'Model' for role in word_roles}
    primitive = {'void': 'V', 'int': 'I', 'long': 'J', 'float': 'F', 'boolean': 'Z'}
    def descriptor(name):
        return primitive.get(name, name.replace('.', '/') if name.startswith('[') else 'L' + name.replace('.', '/') + ';')
    checks = 0
    for group, required in (('methods', required_methods), ('fields', required_fields)):
        declared = data.get(group, {})
        for role in sorted(required - declared.keys()):
            failures.append(f'lyricBlur.{group}.{role}: missing required role')
        for role, contract in declared.items():
            checks += 1
            try:
                native = classes.get(descriptor(contract['owner']), {})
                if group == 'fields':
                    valid = (native.get('fields', {}).get(contract['name']) == descriptor(contract['type'])
                             and not native.get('field_access', {}).get(contract['name'], 8) & 8)
                else:
                    signature = contract['name'] + '(' + ''.join(map(descriptor, contract['parameters'])) + ')' + descriptor(contract['returns'])
                    flags = native.get('method_access', {}).get(signature)
                    forbidden = 0x40 | 0x1000 | (0 if contract.get('invocationOnly') else 0x400)
                    valid = flags is not None and not flags & forbidden and bool(flags & 8) == contract['static']
                if not valid:
                    failures.append(f'lyricBlur.{group}.{role}: invalid exact descriptor')
            except (KeyError, TypeError, AttributeError) as error:
                failures.append(f'lyricBlur.{group}.{role}: incomplete descriptor ({error})')
    return checks


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path, help="APK, XAPK or APKS (all DEX splits are checked)")
    parser.add_argument("--version-name", default=None)
    parser.add_argument("--version-code", default=None)
    parser.add_argument("--profile", type=Path, help="explicit profile, including a disabled research build")
    parser.add_argument("--glass", action="store_true", help="also verify the phone glass seams")
    args = parser.parse_args()

    with zipfile.ZipFile(args.package) as package:
        names = package.namelist()
        manifest = None
        if "manifest.json" in names:
            manifest = json.loads(package.read("manifest.json"))
        base_name = next(
            (name for name in names if name.endswith(".apk") and "config" not in name),
            None,
        )
        if base_name is None:
            base_name = args.package.name
            apk = zipfile.ZipFile(args.package)
        else:
            apk = zipfile.ZipFile(io.BytesIO(package.read(base_name)))
        package_name, actual_name, actual_code = _manifest_module.manifest_identity(apk.read('AndroidManifest.xml'))
        if package_name != 'com.apple.android.music':
            raise SystemExit('Unexpected manifest package: '+package_name)
        version_name = args.version_name or actual_name
        version_code = args.version_code or str(actual_code)
        if (version_name,str(version_code)) != (actual_name,str(actual_code)):
            raise SystemExit(f'Claimed tuple {version_name}/{version_code} differs from binary manifest {actual_name}/{actual_code}')
        if manifest and ((manifest.get('version_name') and manifest['version_name'] != actual_name) or
                         (manifest.get('version_code') and str(manifest['version_code']) != str(actual_code))):
            raise SystemExit('Split package manifest disagrees with base Android manifest')
        profile = PROFILES.get((version_name or "", str(version_code)))
        document = None
        if args.profile:
            document = json.loads(args.profile.read_text(encoding="utf-8"))
            if (document["packageName"], document["versionName"], int(document["versionCode"])) != (
                package_name, actual_name, actual_code
            ):
                raise SystemExit("Explicit profile differs from binary manifest")
            profile = document["verification"]
        if profile is None:
            raise SystemExit(
                "Unsupported or undocumented version tuple: %s (%s). Known profiles: %s"
                % (version_name, version_code, ", ".join("%s (%s)" % key for key in sorted(PROFILES))),
            )
        if version_code != profile["version_code"]:
            raise SystemExit(
                "Version tuple mismatch: %s (%s) does not match the recorded %s profile"
                % (version_name, version_code, profile["version_code"]),
            )

        ALIASED_TYPES = profile.get("aliases", {})
        GLASS_METHODS = profile.get("glass_methods", {})
        LAYOUTS = profile.get("layouts", [])
        classes = {}
        for name in apk.namelist():
            if name.endswith(".dex"):
                classes.update(dex_classes(apk.read(name)))
        available_layouts = set(apk.namelist())
        for split_name in names:
            if not split_name.endswith(".apk") or split_name == base_name:
                continue
            with zipfile.ZipFile(io.BytesIO(package.read(split_name))) as split:
                available_layouts.update(split.namelist())
                for name in split.namelist():
                    if name.endswith(".dex"):
                        classes.update(dex_classes(split.read(name)))

        failures = []
        checks = 0

        if document is None:
            candidate = PROFILE_DIRECTORY / f"{actual_name}-{actual_code}.json"
            if candidate.is_file():
                document = json.loads(candidate.read_text(encoding="utf-8"))
        if document:
            checks += verify_fragment_contracts(document, classes, failures)
            checks += verify_lyric_blur_contract(document, classes, failures)
            def descriptor(name):
                primitive = {'void':'V','int':'I','long':'J','float':'F','boolean':'Z',
                             'double':'D','byte':'B','short':'S','char':'C'}
                return primitive.get(name, name.replace('.', '/') if name.startswith('[')
                                     else 'L' + name.replace('.', '/') + ';')
            for symbol, contract in document['indexed'].get('methodContracts', {}).items():
                checks += 1
                owner = descriptor(contract['owner'])
                signature = contract['name'] + '(' + ''.join(map(descriptor, contract['parameters'])) + ')' + descriptor(contract['returns'])
                flags = classes.get(owner, {}).get('method_access', {}).get(signature)
                # Invocation-only descriptors may name an abstract interface method;
                # reflection dispatches it to the concrete backend. Hook targets still
                # require a non-abstract, non-bridge implementation.
                forbidden = 0x40 | 0x1000 | (0 if contract.get('invocationOnly', False) else 0x400)
                if flags is None or bool(flags & 8) != contract['static'] or flags & forbidden:
                    failures.append(f'invalid exact method contract {symbol}: {owner} {signature}')
            for symbol, contract in document['indexed'].get('fieldContracts', {}).items():
                checks += 1
                owner = descriptor(contract['owner'])
                native = classes.get(owner, {})
                if native.get('fields', {}).get(contract['name']) != descriptor(contract['type']) or native.get('field_access', {}).get(contract['name'], 8) & 8:
                    failures.append(f'invalid exact field contract {symbol}: {owner} {contract["name"]}')

        for owner, signatures in profile["methods"].items():
            if owner not in classes:
                failures.append("missing class %s" % owner)
                continue
            for signature in signatures:
                checks += 1
                where = find_method(classes, owner, signature)
                if where is None:
                    failures.append("missing method %s %s" % (owner, signature))
        for owner, field_names in profile["fields"].items():
            for field_name in field_names:
                checks += 1
                if owner not in classes:
                    failures.append("missing class %s" % owner)
                elif field_name not in classes[owner]["fields"]:
                    failures.append("missing field %s %s" % (owner, field_name))

        for owner, fields in profile.get("fieldTypes", {}).items():
            for name, expected in fields.items():
                checks += 1
                native = classes.get(owner, {})
                if native.get("fields", {}).get(name) != expected or native.get("field_access", {}).get(name, 8) & 8:
                    failures.append("invalid native field descriptor %s %s:%s" % (owner, name, expected))

        for owner, signatures in ALIASED_TYPES.items():
            for signature in signatures:
                checks += 1
                if owner not in classes:
                    failures.append("missing obfuscated type %s" % owner)
                elif signature not in classes[owner]["methods"]:
                    failures.append("missing alias member %s %s" % (owner, signature))

        query = profile.get("catalog_query")
        if query:
            owner = query["owner"]
            checks += 1
            if owner not in classes:
                failures.append("missing catalog query class %s" % owner)
            elif query["verified"] + CATALOG_QUERY_SHAPE not in classes[owner]["methods"]:
                failures.append(
                    "missing catalog query %s %s"
                    % (owner, query["verified"] + CATALOG_QUERY_SHAPE)
                )
            if query["preferred"] != query["verified"]:
                checks += 1
                if owner in classes and (
                    query["preferred"] + CATALOG_QUERY_SHAPE in classes[owner]["methods"]
                ):
                    failures.append(
                        "%s %s satisfies the catalog query shape on %s, so the verified rename %s "
                        "is unreachable" % (
                            owner,
                            query["preferred"],
                            version_name,
                            query["verified"],
                        )
                    )

        interceptor = profile.get("content_http_interceptor")
        if interceptor:
            owner = interceptor["owner"]
            checks += 1
            if owner not in classes:
                failures.append("missing content HTTP interceptor %s" % owner)
            elif interceptor["signature"] not in classes[owner]["methods"]:
                failures.append(
                    "missing content HTTP interceptor method %s %s"
                    % (owner, interceptor["signature"])
                )
            vacated = interceptor.get("vacated")
            if vacated:
                checks += 1
                vacated_owner, vacated_signature = vacated
                if vacated_owner in classes and (
                    vacated_signature in classes[vacated_owner]["methods"]
                ):
                    failures.append(
                        "%s still declares %s on %s, so the pinned interceptor %s is shadowed"
                        % (vacated_owner, vacated_signature, version_name, owner)
                    )

        if args.glass:
            for owner, signatures in GLASS_METHODS.items():
                for signature in signatures:
                    checks += 1
                    if find_method(classes, owner, signature) is None:
                        failures.append("missing glass hook %s %s" % (owner, signature))
            for layout in LAYOUTS:
                checks += 1
                if layout not in available_layouts:
                    failures.append("missing layout %s" % layout)

        print("package: %s" % args.package)
        print("base apk: %s" % base_name)
        print("version tuple: %s (%s)" % (version_name, version_code))
        print("checks: %d, failures: %d" % (checks, len(failures)))
        if failures:
            for failure in failures:
                print("FAIL %s" % failure)
            sys.exit(1)
        print("PASS: Apple Music %s (%s) profile symbols verified" % (version_name, version_code))


if __name__ == "__main__":
    main()
