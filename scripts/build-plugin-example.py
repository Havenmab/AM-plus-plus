#!/usr/bin/env python3
"""Build the sample outside the checkout using only the exported SDK JAR."""
from pathlib import Path
import argparse
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / 'plugin-example')
    args = parser.parse_args()
    if not args.android_sdk:
        parser.error('Pass --android-sdk or set ANDROID_HOME')
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    subprocess.run([str(wrapper), ':plugin-api:exportSdk', '--no-daemon'], cwd=ROOT, check=True)
    sdk = ROOT / 'plugin-api/build/sdk/ampp-plugin-api-v1.jar'
    with tempfile.TemporaryDirectory(prefix='ampp-independent-plugin-') as folder:
        target = Path(folder) / 'basic-plugin'
        shutil.copytree(ROOT / 'examples/basic-plugin', target, ignore=shutil.ignore_patterns('build', '.gradle'))
        (target / 'lib').mkdir()
        shutil.copy2(sdk, target / 'lib' / sdk.name)
        subprocess.run([str(wrapper), '-p', str(target), 'pluginZip',
                        f'-PandroidSdk={Path(args.android_sdk).resolve()}', '--no-daemon'], cwd=target, check=True)
        args.output.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target / 'build/dist/basic-plugin.zip', args.output / 'basic-plugin.zip')
        shutil.copy2(sdk, args.output / sdk.name)
    print(f'Independent sample and SDK: {args.output.resolve()}')

if __name__ == '__main__':
    main()
