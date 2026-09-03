"""Compiles the project after Claude edits a Java source, and says so if it broke.

WHY. A compile error found at commit time is found after several more edits have
been piled on top of it, and the one that caused it is no longer obvious. Found
immediately it is one line and one fix.

WHY NOT MAVEN. Measured on this machine: `mvn -o -q test-compile` takes 11
seconds, `javac` over the same sources takes 5. Almost all the difference is
Maven starting up, and it is paid on EVERY edit. Five seconds is a pause; eleven
is an interruption, and an interruption gets switched off. So javac, with the
dependency classpath resolved by Maven once and cached until the pom changes.

WHAT IT DOES NOT DO. It does not run the tests -- that is the git hook's job, at
commit time, once. Compiling is the cheap question ("is this still Java?"); the
suite is the expensive one.

It cannot block: PostToolUse runs after the edit is already written. It reports.
Exit 2 puts the compiler's own message in front of Claude, which is the point --
the error arrives as text to act on, not as a status to notice.

WIRED UP in .claude/settings.json of this project.
"""

import glob
import json
import os
import subprocess
import sys

NEEDED = 17

HERE = os.path.dirname(os.path.abspath(__file__))

CACHE = os.path.join(HERE, ".classpath")


def usable(home):
    javac = os.path.join(home, "bin", "javac.exe") if home else ""

    if not home or not os.path.isfile(javac):
        return False

    try:
        out = subprocess.run([javac, "-version"], capture_output=True, text=True, timeout=20)
    except (OSError, subprocess.SubprocessError):
        return False

    parts = (out.stdout + out.stderr).split()

    return len(parts) > 1 and parts[1].split(".")[0].isdigit() \
        and int(parts[1].split(".")[0]) >= NEEDED


def find_jdk():
    """A JDK new enough to build this project, wherever it is on the machine.

    On a machine whose only JDKs arrived inside JetBrains IDEs -- the normal case
    on Windows -- nothing is on the PATH and JAVA_HOME is unset. A hook that
    gives up there is a hook that never runs.
    """
    if usable(os.environ.get("JAVA_HOME", "")):
        return os.environ["JAVA_HOME"]

    patterns = [
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "JetBrains", "*", "jbr"),
        r"C:\Program Files\Eclipse Adoptium\jdk*",
        r"C:\Program Files\Java\jdk*",
    ]

    found = [p for pattern in patterns for p in glob.glob(pattern) if usable(p)]

    return found[-1] if found else None


def find_maven(root):
    for name in ("mvnw.cmd", "mvnw"):
        if os.path.isfile(os.path.join(root, name)):
            return os.path.join(root, name)

    # Where the Maven wrapper unpacks what it downloads: the IDE puts it there
    # and uses it without ever touching the PATH.
    found = glob.glob(os.path.join(
        os.path.expanduser("~"), ".m2", "wrapper", "dists", "*", "*", "*", "bin", "mvn.cmd"))

    return found[-1] if found else None


def classpath(root, jdk):
    """The test-scope classpath, resolved by Maven once and cached.

    Keyed on the pom's modification time: a dependency added to the pom must not
    go on being compiled against yesterday's classpath, which would report an
    import error that does not exist.
    """
    pom = os.path.join(root, "pom.xml")
    stamp = str(os.path.getmtime(pom))

    if os.path.isfile(CACHE):
        with open(CACHE, encoding="utf-8") as f:
            cached = f.read().split("\n", 1)

        if len(cached) == 2 and cached[0] == stamp:
            return cached[1]

    maven = find_maven(root)

    if not maven:
        return None

    out = os.path.join(HERE, ".classpath.tmp")
    result = subprocess.run(
        [maven, "-o", "-q", "dependency:build-classpath",
         "-Dmdep.outputFile=" + out, "-Dmdep.includeScope=test"],
        cwd=root, env=dict(os.environ, JAVA_HOME=jdk),
        capture_output=True, text=True, timeout=300)

    if result.returncode != 0 or not os.path.isfile(out):
        return None

    with open(out, encoding="utf-8") as f:
        resolved = f.read().strip()

    os.remove(out)

    with open(CACHE, "w", encoding="utf-8") as f:
        f.write(stamp + "\n" + resolved)

    return resolved


def main():
    try:
        event = json.load(sys.stdin)
    except ValueError:
        return

    path = str((event.get("tool_input") or {}).get("file_path", ""))

    # Only Java, and only sources. Editing a .md or a file elsewhere must not
    # cost a compile.
    if not path.endswith(".java") or (os.path.normcase("src") + os.sep) \
            not in os.path.normcase(path):
        return

    root = event.get("cwd") or os.getcwd()

    if not os.path.isfile(os.path.join(root, "pom.xml")):
        return

    jdk = find_jdk()

    if not jdk:
        # Said out loud rather than passing in silence: a hook believed to be
        # running and doing nothing is worse than no hook.
        sys.stderr.write("compile hook: no JDK %d+ found; nothing was compiled\n" % NEEDED)
        sys.exit(2)

    sources = [p for tree in ("src/main/java", "src/test/java")
               for p in glob.glob(os.path.join(root, tree, "**", "*.java"), recursive=True)]

    if not sources:
        return

    cp = classpath(root, jdk)

    if cp is None:
        sys.stderr.write("compile hook: could not resolve the classpath; nothing was compiled\n")
        sys.exit(2)

    # Its own output directory. Writing into target/classes would leave Maven's
    # idea of what is up to date disagreeing with what is on disk.
    out = os.path.join(root, "target", "hook-classes")

    os.makedirs(out, exist_ok=True)

    result = subprocess.run(
        [os.path.join(jdk, "bin", "javac"), "-d", out, "-encoding", "UTF-8",
         "--release", str(NEEDED), "-nowarn", "-cp", cp] + sources,
        cwd=root, capture_output=True, text=True, timeout=300)

    if result.returncode == 0:
        return

    lines = (result.stderr + result.stdout).splitlines()
    errors = [line for line in lines if ": error:" in line]

    sys.stderr.write("the project no longer compiles:\n"
                     + "\n".join(errors[:12] or lines[:12]) + "\n")
    sys.exit(2)


if __name__ == "__main__":
    main()
