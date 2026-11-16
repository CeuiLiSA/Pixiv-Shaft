"""Regression tests for offline tunnel asset generation.

Run with: python3 -m unittest discover -s scripts -p 'test_shrink_prime_square.py'
"""
import contextlib
import io
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from PIL import Image

import shrink_prime_square as builder


class AtlasGenerationTest(unittest.TestCase):
    def test_every_slot_keeps_near_field_detail(self):
        # hash21(id) can select any slot in the foreground. A one-pixel pattern
        # must therefore survive in the last slots just as it does in the first.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.png"
            pattern = bytes(255 if x % 2 else 0 for _ in range(200) for x in range(200))
            Image.frombytes("L", (200, 200), pattern).save(source)
            output = root / "atlas.webp"
            builder.build_atlas([source], output, root / "sample.webp", quality=100)
            with Image.open(output) as atlas:
                self.assertEqual((5600, 3200), atlas.size)
                for index in (0, 111, 112, 223, 224, 447):
                    with self.subTest(index=index):
                        x = (index % 28) * 200
                        y = (index // 28) * 200
                        tile = atlas.crop((x + 20, y + 20, x + 180, y + 180))
                        low, high = tile.convert("L").getextrema()
                        self.assertLess(low, 30)
                        self.assertGreater(high, 225)


class GitSourceRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.assets = self.root / "app/src/main/assets/prime_square"
        self.assets.mkdir(parents=True)
        self.git("init", "--quiet")
        self.patches = mock.patch.multiple(builder, ROOT=self.root, TARGET_DIR=self.assets)
        self.patches.start()
        self.addCleanup(self.patches.stop)

    def git(self, *args):
        return subprocess.check_output(
            ["git", "-c", "user.name=Atlas Test", "-c", "user.email=atlas@example.com",
             "-c", "commit.gpgsign=false", *args],
            cwd=self.root,
        )

    def commit_assets(self):
        self.git("add", "--", "app/src/main/assets/prime_square")
        self.git("commit", "--quiet", "-m", "Asset fixture")

    def test_recovers_latest_sources_after_deletion_from_another_cwd(self):
        source = self.assets / "tile.webp"
        Image.new("RGB", (200, 200), "red").save(source)
        self.commit_assets()
        Image.new("RGB", (200, 200), "blue").save(source)
        expected = source.read_bytes()
        self.commit_assets()
        source.unlink()
        Image.new("RGB", (560, 320), "black").save(self.assets / "tunnel_atlas.webp")
        Image.new("RGB", (200, 200), "green").save(self.assets / "preview_sample.webp")
        self.commit_assets()
        # Make HEAD differ from the last asset-changing commit as well.
        (self.root / "README").write_text("fixture")
        self.git("add", "--", "README")
        self.git("commit", "--quiet", "-m", "Unrelated change")

        with contextlib.chdir(self.root.parent):
            self.assertEqual([expected], builder.load_from_git())

    def test_missing_sources_cannot_report_success_for_an_old_atlas(self):
        Image.new("RGB", (560, 320), "black").save(self.assets / "tunnel_atlas.webp")
        self.commit_assets()
        with mock.patch("sys.argv", ["shrink_prime_square.py", "--quality", "95"]):
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, builder.main())


if __name__ == "__main__":
    unittest.main()
