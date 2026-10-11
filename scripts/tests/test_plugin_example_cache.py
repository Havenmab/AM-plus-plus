"""Regression checks for SDK/example artifact cache invalidation."""
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('plugin_example', Path(__file__).resolve().parents[1] / 'build-plugin-example.py')
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


class PluginExampleCacheTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.base = Path(self.folder.name)
        self.root = self.base / 'checkout'
        self.sdk = self.base / 'android'
        self.jdk = self.base / 'jdk'
        self.inputs = [
            'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
            'gradlew', 'gradlew.bat', 'scripts/build-plugin-example.py',
            'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar',
            'gradle/libs.versions.toml', 'plugin-api/build.gradle.kts',
            'plugin-api/src/main/AndroidManifest.xml', 'plugin-api/src/main/java/Api.java',
            'examples/basic-plugin/build.gradle.kts', 'examples/basic-plugin/settings.gradle.kts',
            'examples/basic-plugin/src/main/java/Plugin.java', 'examples/basic-plugin/plugin.json',
            'examples/basic-plugin/assets/message.txt', 'host-applemusic/build.gradle.kts',
            'buildSrc/src/main/kotlin/BuildLogic.kt',
        ]
        for name in self.inputs:
            self.write(self.root / name, name)
        self.tools = [self.sdk / 'platforms/android-37.0/android.jar',
                      self.sdk / 'build-tools/37.0.0/lib/d8.jar', self.jdk / 'release']
        for path in self.tools:
            self.write(path, path.name)

    @staticmethod
    def write(path, contents):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding='utf-8')

    def key(self, root=None, sdk=None, jdk=None):
        return BUILDER.cache_key(sdk or self.sdk, jdk or self.jdk, root or self.root)

    def test_source_configuration_and_tool_changes_invalidate(self):
        baseline = self.key()
        for path in [self.root / name for name in self.inputs] + self.tools:
            with self.subTest(path=path):
                original = path.read_bytes()
                path.write_bytes(original + b'changed')
                self.assertNotEqual(baseline, self.key())
                path.write_bytes(original)
        # Add/remove input files as well as edits.
        extra = self.root / 'plugin-api/src/main/java/NewApi.java'
        self.write(extra, 'new API')
        self.assertNotEqual(baseline, self.key())
        extra.unlink()
        self.assertEqual(baseline, self.key())

    def test_generated_outputs_and_unrelated_application_sources_do_not_invalidate(self):
        baseline = self.key()
        for name in ('build/plugin-example/basic-plugin.zip', 'plugin-api/build/sdk/output.jar',
                     'examples/basic-plugin/build/dist/basic-plugin.zip',
                     'examples/basic-plugin/.gradle/state', 'gradle/__pycache__/state',
                     'host-applemusic/src/main/java/Player.kt'):
            self.write(self.root / name, 'generated or unrelated')
        self.assertEqual(baseline, self.key())

    def test_key_is_independent_of_checkout_and_tool_install_paths(self):
        relocated = self.base / 'relocated'
        for source in (self.root, self.sdk, self.jdk):
            shutil.copytree(source, relocated / source.name)
        self.assertEqual(self.key(), self.key(relocated / self.root.name,
                                             relocated / self.sdk.name, relocated / self.jdk.name))

    def test_android_platform_alias_matches_the_builder_and_missing_tools_fail(self):
        baseline = self.key()
        jar = self.tools[0]
        jar.rename(jar.parent / 'old.jar')
        self.write(self.sdk / 'platforms/android-37/android.jar', 'android.jar')
        self.assertEqual(baseline, self.key())
        self.tools[1].unlink()
        with self.assertRaises(FileNotFoundError):
            self.key()


if __name__ == '__main__':
    unittest.main()
