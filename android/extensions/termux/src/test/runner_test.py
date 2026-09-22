"""Linux acceptance tests for the exact bundled Termux bash supervisor."""
import base64
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest
import uuid


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.TemporaryDirectory(prefix="mochi-termux-test-")
        self.root = Path(self.home.name) / ".local/state/mochi-termux"
        self.root.mkdir(parents=True)
        self.runner = self.root / "runner-v1"
        shutil.copyfile(Path(__file__).parents[1] / "main/assets/runner-v1.sh", self.runner)
        self.env = dict(os.environ, HOME=self.home.name)
        self.processes = []

    def tearDown(self):
        for task_id, process in self.processes:
            if process.poll() is None:
                self.call("stop", task_id)
                process.communicate(timeout=8)
        self.home.cleanup()

    def call(self, *args):
        return subprocess.run(
            ["bash", str(self.runner), *args], env=self.env,
            capture_output=True, text=True, timeout=10, check=True,
        ).stdout

    def start(self, command, seconds=5, workdir=None):
        task_id = str(uuid.uuid4())
        command64 = base64.b64encode(command.encode()).decode()
        process = subprocess.Popen(
            ["bash", str(self.runner), "run", task_id, command64,
             workdir or self.home.name, str(seconds)],
            env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.processes.append((task_id, process))
        return task_id, process

    def result(self, process):
        output, error = process.communicate(timeout=10)
        self.assertEqual(0, process.returncode, error)
        lines = output.splitlines()
        self.assertEqual(6, len(lines), (output, error))
        self.assertEqual("MOCHI_TASK_V1", lines[0])
        return lines

    def test_probe_and_stdout_stderr_exit_code(self):
        self.assertEqual("MOCHI_READY_V1\n", self.call("probe"))
        task_id, process = self.start("printf 'hello'; printf 'problem' >&2; exit 7")
        lines = self.result(process)
        self.assertEqual(["failed", "7"], lines[1:3])
        self.assertEqual(b"hello", base64.b64decode(lines[3]))
        self.assertEqual(b"problem", base64.b64decode(lines[4]))
        self.assertEqual("\n".join(lines) + "\n", self.call("read", task_id))
        self.call("forget", task_id)
        self.assertFalse((self.root / task_id).exists())

    def test_arbitrary_pipes_and_workdir_are_preserved(self):
        folder = Path(self.home.name) / "a 'quoted' directory"
        folder.mkdir()
        _, process = self.start("printf abc | tr a-z A-Z > result; cat result", workdir=str(folder))
        lines = self.result(process)
        self.assertEqual("succeeded", lines[1])
        self.assertEqual("ABC", (folder / "result").read_text())
        self.assertEqual(b"ABC", base64.b64decode(lines[3]))

    def test_timeout_kills_owned_process_group(self):
        task_id, process = self.start("sleep 20; touch should-not-exist", seconds=1)
        lines = self.result(process)
        self.assertEqual("timed_out", lines[1])
        self.assertFalse((Path(self.home.name) / "should-not-exist").exists())
        self.assertIn("timed_out", self.call("read", task_id))

    def test_stop_is_not_just_cancelling_the_callback(self):
        task_id, process = self.start("sleep 20; touch should-not-exist")
        deadline = time.monotonic() + 5
        while not (self.root / task_id / "process").exists():
            self.assertLess(time.monotonic(), deadline)
            time.sleep(0.02)
        self.call("stop", task_id)
        self.assertEqual("stopped", self.result(process)[1])
        self.assertFalse((Path(self.home.name) / "should-not-exist").exists())

    def test_large_output_is_drained_but_storage_is_bounded(self):
        task_id, process = self.start("head -c 100000 /dev/zero")
        lines = self.result(process)
        self.assertEqual("succeeded", lines[1])
        self.assertEqual(16384, len(base64.b64decode(lines[3])))
        self.assertEqual("1", lines[5])
        self.assertEqual(16385, (self.root / task_id / "stdout").stat().st_size)

    def test_missing_workdir_is_failure_not_success(self):
        _, process = self.start("echo should-not-run", workdir="/mochi-nonexistent-test-directory")
        lines = self.result(process)
        self.assertEqual("failed", lines[1])
        self.assertNotEqual("0", lines[2])

    def test_forged_task_path_is_rejected(self):
        result = subprocess.run(
            ["bash", str(self.runner), "forget", "../unrelated"],
            env=self.env, capture_output=True, timeout=5,
        )
        self.assertNotEqual(0, result.returncode)

    def test_stop_before_process_creation_prevents_execution(self):
        task_id = str(uuid.uuid4())
        self.call("stop", task_id)
        command64 = base64.b64encode(b"touch should-not-exist").decode()
        result = self.call("run", task_id, command64, self.home.name, "5")
        self.assertIn("\nstopped\n0\n", result)
        self.assertFalse((Path(self.home.name) / "should-not-exist").exists())

    def test_stop_escalates_when_a_child_ignores_term(self):
        task_id, process = self.start("trap '' TERM; sleep 6; touch should-not-exist", seconds=10)
        deadline = time.monotonic() + 5
        while not (self.root / task_id / "process").exists():
            self.assertLess(time.monotonic(), deadline)
            time.sleep(0.02)
        self.call("stop", task_id)
        self.assertEqual("stopped", self.result(process)[1])
        self.assertFalse((Path(self.home.name) / "should-not-exist").exists())


if __name__ == "__main__":
    unittest.main()
