#!/usr/bin/env python3
"""Build the sample outside the checkout using only the exported SDK JAR."""
from pathlib import Path
import argparse
import hashlib
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def cache_key(android_sdk, java_home, root=ROOT):
    """Fingerprint the independent SDK/example inputs, including the actual toolchain."""
    inputs = {}
    for name in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
                 'gradlew', 'gradlew.bat', 'scripts/build-plugin-example.py'):
        inputs[name] = root / name
    # Include build logic/configuration, but not application sources or generated outputs.
    for folder in ('gradle', 'plugin-api', 'examples/basic-plugin', 'buildSrc', 'build-logic'):
        directory = root / folder
        if not directory.exists():
            continue
        for path in directory.rglob('*'):
            relative = path.relative_to(directory)
            if path.is_file() and not {'build', '.gradle', '.kotlin', '__pycache__'} & set(relative.parts):
                inputs[path.relative_to(root).as_posix()] = path
    for path in root.glob('*/build.gradle.kts'):
        inputs[path.relative_to(root).as_posix()] = path

    android_sdk = Path(android_sdk)
    android_jar = next((android_sdk / 'platforms' / platform / 'android.jar'
                        for platform in ('android-37.0', 'android-37')
                        if (android_sdk / 'platforms' / platform / 'android.jar').is_file()), None)
    if android_jar is None:
        raise FileNotFoundError('Install Android platform 37')
    inputs['toolchain/android.jar'] = android_jar
    inputs['toolchain/d8.jar'] = android_sdk / 'build-tools/37.0.0/lib/d8.jar'
    inputs['toolchain/java-release'] = Path(java_home) / 'release'
    digest = hashlib.sha256()
    for name, path in sorted(inputs.items()):
        digest.update(name.encode('utf-8') + b'\0')
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / 'plugin-example')
    parser.add_argument('--cache-key', action='store_true', help='Print the exact SDK/example cache key without building')
    args = parser.parse_args()
    if not args.android_sdk:
        parser.error('Pass --android-sdk or set ANDROID_HOME')
    if args.cache_key:
        if not os.environ.get('JAVA_HOME'):
            parser.error('Set JAVA_HOME to fingerprint the JDK')
        print(cache_key(args.android_sdk, os.environ['JAVA_HOME']))
        return
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    subprocess.run([str(wrapper), ':plugin-api:exportSdk', '--no-daemon'], cwd=ROOT, check=True)
    sdk = ROOT / 'plugin-api/build/sdk/ampp-plugin-api-v1.jar'
    with tempfile.TemporaryDirectory(prefix='ampp-independent-plugin-') as folder:
        target = Path(folder) / 'basic-plugin'
        shutil.copytree(ROOT / 'examples/basic-plugin', target, ignore=shutil.ignore_patterns('build', '.gradle'))
        (target / 'lib').mkdir()
        shutil.copy2(sdk, target / 'lib' / sdk.name)
        subprocess.run([str(wrapper), '-p', str(target), 'pluginZip',
                        f'-PandroidSdk={Path(args.android_sdk).resolve()}', '--build-cache', '--no-daemon'], cwd=target, check=True)
        args.output.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target / 'build/dist/basic-plugin.zip', args.output / 'basic-plugin.zip')
        shutil.copy2(sdk, args.output / sdk.name)
    print(f'Independent sample and SDK: {args.output.resolve()}')

if __name__ == '__main__':
    main()
