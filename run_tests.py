#!/usr/bin/env python3
"""run_tests.py -- run the pure-JVM logic tests.

Only StitchEngine (crop arithmetic / linked-crop rules) and the test harness are
compiled. android.jar is used purely as a compile-time classpath: none of the
tested code touches a real Android class at runtime, so the tests run on any
desktop JVM.

Usage:
    python run_tests.py [--sdk PATH] [--java-home PATH]

Exit code 0 means every test passed.
"""
import argparse
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, 'build', 'testclasses')
PLATFORM = 'android-36'


def load_local_properties():
    props = {}
    path = os.path.join(ROOT, 'local.properties')
    if os.path.isfile(path):
        with open(path, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#') and '=' in line:
                    k, v = line.split('=', 1)
                    props[k.strip()] = v.strip()
    return props


def resolve(args):
    props = load_local_properties()
    sdk = (args.sdk or os.environ.get('ANDROID_SDK_ROOT')
           or os.environ.get('ANDROID_HOME') or props.get('sdk.dir'))
    if not sdk or not os.path.isdir(sdk):
        guess = os.path.join(os.path.expanduser('~'), 'Android', 'Sdk')
        sdk = guess if os.path.isdir(guess) else sdk
    java_home = (args.java_home or os.environ.get('JAVA_HOME')
                 or props.get('java.home'))
    return sdk, java_home


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--sdk')
    ap.add_argument('--java-home', dest='java_home')
    args = ap.parse_args()

    sdk, java_home = resolve(args)
    android_jar = os.path.join(sdk or '', 'platforms', PLATFORM, 'android.jar')
    javac = os.path.join(java_home or '', 'bin', 'javac.exe')
    java = os.path.join(java_home or '', 'bin', 'java.exe')

    problems = []
    if not os.path.isfile(android_jar):
        problems.append('android.jar not found at %s' % android_jar)
    if not os.path.isfile(javac):
        problems.append('javac not found at %s' % javac)
    if problems:
        print('Configuration problems:')
        for p in problems:
            print('  - ' + p)
        print('\nSet ANDROID_SDK_ROOT / JAVA_HOME, or use local.properties '
              '(sdk.dir / java.home).')
        sys.exit(2)

    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)

    sources = [
        os.path.join(ROOT, 'src', 'com', 'dsh', 'subtitlestitch', 'StitchEngine.java'),
        os.path.join(ROOT, 'test', 'com', 'dsh', 'subtitlestitch', 'CropLogicTest.java'),
    ]

    env = dict(os.environ)
    if java_home:
        env['JAVA_HOME'] = java_home
        env['PATH'] = os.path.join(java_home, 'bin') + os.pathsep + env.get('PATH', '')

    print('==> compile')
    r = subprocess.run([javac, '-encoding', 'UTF-8', '-nowarn',
                        '-classpath', android_jar, '-d', OUT] + sources,
                       env=env, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        print('COMPILE FAILED')
        sys.exit(1)

    print('==> run')
    r = subprocess.run([java, '-cp', OUT, 'com.dsh.subtitlestitch.CropLogicTest'],
                       env=env, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    print(r.stdout.rstrip())
    if r.returncode != 0:
        print(r.stderr)
        print('TESTS FAILED')
        sys.exit(1)
    print('ALL TESTS PASSED')


if __name__ == '__main__':
    main()
