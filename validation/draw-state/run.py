#!/usr/bin/env python3
"""Compile pinned sources and run native probes with caller-supplied dependencies.

No installation, downloads, checkout, patching, or production-tree writes occur.
The output directory must be new or empty and outside the source checkout.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
SOURCE_PATHS = (
    "jme3-core/src/main/java/com/jme3/renderer/opengl/GLRenderer.java",
    "jme3-core/src/main/java/com/jme3/renderer/RenderContext.java",
)
# name, pinned source version, harness, mode, expected behavior
RUNS = (
    ("stencil-baseline", "baseline", "DrawStatePixelSmoke", "stencil", "baseline"),
    ("stencil-pr2987", "pr2987", "DrawStatePixelSmoke", "stencil", "fixed"),
    ("divisor-baseline", "baseline", "DrawStatePixelSmoke", "divisor", "baseline"),
    ("divisor-pr2988", "pr2988", "DrawStatePixelSmoke", "divisor", "fixed"),
    ("invalidation-baseline", "baseline", "InvalidationPixelSmoke", "divisor", "baseline"),
    ("invalidation-pr2988", "pr2988", "InvalidationPixelSmoke", "divisor", "pr2988"),
    ("invalidation-pr2989", "pr2989", "InvalidationPixelSmoke", "divisor", "repaired"),
    ("core-vao-pr2989", "pr2989", "InvalidationCoreVaoSmoke", None, None),
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def tree_digest(path):
    """Content digest using sorted relative filenames, never private absolute paths."""
    if path.is_file():
        return {"type": "file", "sha256": digest(path.read_bytes())}
    value = hashlib.sha256()
    files = sorted(item for item in path.rglob("*") if item.is_file())
    for item in files:
        name = item.relative_to(path).as_posix().encode("utf-8")
        value.update(name + b"\0" + hashlib.sha256(item.read_bytes()).digest())
    return {"type": "directory", "files": len(files), "sha256": value.hexdigest()}


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True,
                        help="Existing local jMonkeyEngine Git clone containing every pin")
    parser.add_argument("--java-home", type=Path, required=True,
                        help="Installed JDK 21 or newer")
    parser.add_argument("--dependency-classpath", required=True,
                        help="Installed JNA, compatible jME core/desktop classes and core resources")
    parser.add_argument("--output", type=Path, required=True,
                        help="New or empty output directory outside the source checkout")
    args = parser.parse_args()
    repository = args.repository.resolve()
    java_home = args.java_home.resolve()
    output = args.output.resolve()
    dependencies = [Path(value).resolve() for value in args.dependency_classpath.split(os.pathsep)]
    if not all(path.exists() for path in dependencies):
        parser.error("Every dependency-classpath entry must exist; wildcards are unsupported")
    if output == repository or repository in output.parents:
        parser.error("Output must be outside the source checkout")
    if output == HERE or HERE in output.parents:
        parser.error("Output must be outside this validation package")
    if output.exists() and any(output.iterdir()):
        parser.error("Output directory must be new or empty")
    java = java_home / "bin" / "java"
    javac = java_home / "bin" / "javac"
    if not java.is_file() or not javac.is_file():
        parser.error("java-home must contain bin/java and bin/javac")

    environment = os.environ.copy()
    environment.update(MESA_SHADER_CACHE_DISABLE="true", LP_NUM_THREADS="2",
                       LIBGL_ALWAYS_SOFTWARE="true", GIT_OPTIONAL_LOCKS="0",
                       GIT_NO_LAZY_FETCH="1", LC_ALL="C", TZ="UTC")
    # Avoid injecting unrelated JVM agents, options, or private paths into the run.
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
        environment.pop(name, None)
    replacements = {str(repository): "<repository>", str(java_home): "<java-home>",
                    str(output): "<output>", str(HERE): "<validation-package>"}
    for index, path in enumerate(dependencies):
        replacements[str(path)] = f"<dependency-{index}>"

    def sanitize(text):
        for private, replacement in sorted(replacements.items(), key=lambda pair: -len(pair[0])):
            text = text.replace(private, replacement)
        return text

    def execute(command, *, timeout=120):
        result = subprocess.run(command, env=environment, capture_output=True,
                                text=True, timeout=timeout, cwd=output)
        return result.returncode, sanitize(result.stdout + result.stderr)

    output.mkdir(parents=True, exist_ok=True)
    build = output / "build"
    evidence = output / "evidence"
    build.mkdir()
    evidence.mkdir()
    java_status, java_version = execute([str(java), "-version"])
    if java_status or not re.search(r'version "(?:2[1-9]|[3-9]\d|\d{3,})[.\"]', java_version):
        raise RuntimeError("This harness requires installed Java 21 or newer")
    (evidence / "java-version.log").write_text(java_version, encoding="utf-8")
    classpath = os.pathsep.join(str(path) for path in dependencies)
    pins = json.loads((HERE / "pins.json").read_text(encoding="utf-8"))
    manifest = {
        "format_version": 1,
        "source_repository": "https://github.com/jMonkeyEngine/jmonkeyengine",
        "commit_host": "https://github.com/toaster0123/jmonkeyengine",
        "pins": pins,
        "dependency_classpath_entries": [tree_digest(path) for path in dependencies],
        "harness_sha256": {path.relative_to(HERE).as_posix(): digest(path.read_bytes())
                           for path in sorted((HERE / "src").glob("*.java"))},
        "runner_sha256": digest(Path(__file__).read_bytes()),
        "command_template": "python3 run.py --repository <repository> --java-home <java-home> "
                            "--dependency-classpath <installed-dependencies> --output <output>",
        "environment": {name: environment[name] for name in (
            "MESA_SHADER_CACHE_DISABLE", "LP_NUM_THREADS", "LIBGL_ALWAYS_SOFTWARE", "LC_ALL", "TZ")},
        "results": [],
    }
    write_json(evidence / "manifest.json", manifest)
    compile_options = [str(javac), "-J-XX:ActiveProcessorCount=2", "-sourcepath", "", "-cp", classpath]
    harness_classes = build / "harness-classes"
    harness_classes.mkdir()
    code, log = execute(compile_options + ["-d", str(harness_classes)]
                        + [str(path) for path in sorted((HERE / "src").glob("*.java"))])
    (evidence / "harness-compile.log").write_text(log, encoding="utf-8")
    if code:
        raise RuntimeError("Harness compilation failed; see evidence/harness-compile.log")

    classes_by_version = {}
    for version, pin in pins.items():
        sources = build / version / "src"
        classes = build / version / "classes"
        sources.mkdir(parents=True)
        classes.mkdir()
        for relative in SOURCE_PATHS:
            # All protocols are prohibited, including a promisor remote's lazy fetch.
            command = ["git", "--no-optional-locks", "-c", "protocol.allow=never", "-C",
                       str(repository), "show", f"{pin['commit']}:{relative}"]
            result = subprocess.run(command, env=environment, capture_output=True, timeout=30)
            if result.returncode:
                raise RuntimeError(f"Pinned source missing locally: {version}/{relative}")
            expected = pin["source_sha256"][relative]
            if digest(result.stdout) != expected:
                raise RuntimeError(f"Pinned source hash mismatch: {version}/{relative}")
            (sources / Path(relative).name).write_bytes(result.stdout)
        code, log = execute(compile_options + ["-d", str(classes)]
                            + [str(path) for path in sorted(sources.glob("*.java"))])
        (evidence / f"{version}-compile.log").write_text(log, encoding="utf-8")
        if code:
            raise RuntimeError(f"Production source compilation failed: {version}")
        classes_by_version[version] = classes

    for name, version, harness, mode, expectation in RUNS:
        destination = evidence / name
        destination.mkdir()
        run_classpath = os.pathsep.join((str(harness_classes),
                                        str(classes_by_version[version]), classpath))
        command = [str(java), "-ea", "-XX:-UsePerfData", "-XX:ActiveProcessorCount=2",
                   "-Duser.timezone=UTC", "-Djava.util.logging.SimpleFormatter.format=%4$s: %5$s%n",
                   "-cp", run_classpath, harness]
        if mode is not None:
            command += [mode, expectation, str(destination / "native-state-calls.log")]
        code, log = execute(command, timeout=90)
        (destination / "console.log").write_text(log, encoding="utf-8")
        summary = next((line for line in log.splitlines() if line.startswith("PASS ")), None)
        manifest["results"].append({
            "name": name, "source_version": version, "commit": pins[version]["commit"],
            "harness": harness, "mode": mode, "expectation": expectation,
            "exit_code": code, "summary": summary,
            "console": f"{name}/console.log",
            "native_calls": f"{name}/native-state-calls.log" if mode else None,
        })
        write_json(evidence / "manifest.json", manifest)
        print(f"{name}: {summary or 'FAILED (see evidence log)'}", flush=True)
        if code or summary is None:
            return code or 1

    evidence_files = sorted(path for path in evidence.rglob("*") if path.is_file())
    (evidence / "SHA256SUMS").write_text("".join(
        f"{digest(path.read_bytes())}  {path.relative_to(evidence).as_posix()}\n"
        for path in evidence_files), encoding="utf-8")
    print("All expected outcomes established; baseline PASS means the bug was reproduced.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"Validation failed: {error}", file=sys.stderr)
        sys.exit(1)
