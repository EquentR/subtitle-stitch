#!/usr/bin/env python3
"""build.py -- build the Subtitle Stitch APK without Gradle.

Drives the Android SDK command line tools directly:
    aapt2 compile -> aapt2 link -> javac -> d8 -> package -> zipalign -> apksigner

Configuration is taken from (highest priority first):
    1. command line flags (--sdk, --java-home, --keystore ...)
    2. environment variables (ANDROID_SDK_ROOT / ANDROID_HOME, JAVA_HOME,
       STITCH_KEYSTORE, STITCH_KS_PASS, STITCH_KS_ALIAS)
    3. local.properties in the project root (git-ignored), supports:
           sdk.dir=...
           java.home=...
           keystore.path=...
           keystore.pass=...
           keystore.alias=...
    4. sensible defaults (SDK under ~/Android/Sdk, buildsigned with a
       throwaway debug key stored in build/debug.keystore)

Signing:
    * --key/--cert   sign with a raw PKCS#8 PEM key + X.509 cert
    * otherwise      use a PKCS#12 keystore (created automatically for debug)

Usage:
    python build.py [--release] [--install] [--serial SERIAL]
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, 'build')
SRC = os.path.join(ROOT, 'src')
RES = os.path.join(ROOT, 'res')
MANIFEST = os.path.join(ROOT, 'AndroidManifest.xml')

PACKAGE = 'com.dsh.subtitlestitch'
VERSION_NAME = '1.1.0'
VERSION_CODE = 2
MIN_SDK = '29'
TARGET_SDK = '36'

# SDK components are looked up by these exact names.
BUILD_TOOLS_VERSION = '36.0.0'
PLATFORM = 'android-36'


# --------------------------------------------------------------------- config

def read_local_properties():
    path = os.path.join(ROOT, 'local.properties')
    props = {}
    if not os.path.isfile(path):
        return props
    with open(path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            k, v = line.split('=', 1)
            props[k.strip()] = v.strip()
    return props


class Config:
    def __init__(self, args):
        props = read_local_properties()

        def pick(cli, env_name, prop_name, default=None):
            if cli:
                return cli
            if env_name and os.environ.get(env_name):
                return os.environ[env_name]
            if prop_name and props.get(prop_name):
                return props[prop_name]
            return default

        self.sdk = pick(args.sdk, 'ANDROID_SDK_ROOT',
                        'sdk.dir', os.environ.get('ANDROID_HOME'))
        if not self.sdk or not os.path.isdir(self.sdk):
            guess = os.path.join(os.path.expanduser('~'), 'Android', 'Sdk')
            if os.path.isdir(guess):
                self.sdk = guess
        self.java_home = pick(args.java_home, 'JAVA_HOME', 'java.home')

        self.ks_path = pick(args.keystore, 'STITCH_KEYSTORE', 'keystore.path')
        self.ks_pass = pick(args.ks_pass, 'STITCH_KS_PASS', 'keystore.pass', 'android')
        self.ks_alias = pick(args.ks_alias, 'STITCH_KS_ALIAS', 'keystore.alias', 'debug')
        self.key_pem = args.key
        self.cert_pem = args.cert
        self.serial = args.serial
        self.adb = args.adb

    def validate(self):
        problems = []
        if not self.sdk or not os.path.isdir(self.sdk):
            problems.append(
                'Android SDK not found. Set ANDROID_SDK_ROOT or sdk.dir in '
                'local.properties (see README).')
        else:
            if not os.path.isfile(self.build_tool('aapt2.exe')):
                problems.append('aapt2 missing; install build-tools;%s'
                                % BUILD_TOOLS_VERSION)
            if not os.path.isfile(self.android_jar()):
                problems.append('android.jar missing; install platforms;%s' % PLATFORM)
        if not self.java_home or not os.path.isfile(
                os.path.join(self.java_home, 'bin', 'javac.exe')):
            problems.append('JDK 17+ not found. Set JAVA_HOME or java.home.')
        if problems:
            print('Configuration problems:')
            for p in problems:
                print('  - ' + p)
            print()
            print('Example local.properties:')
            print('  sdk.dir=C:/Users/you/AppData/Local/Android/Sdk')
            print('  java.home=C:/Program Files/Java/jdk-21')
            sys.exit(2)

    def build_tool(self, name):
        return os.path.join(self.sdk, 'build-tools', BUILD_TOOLS_VERSION, name)

    def android_jar(self):
        return os.path.join(self.sdk, 'platforms', PLATFORM, 'android.jar')

    def java(self, name):
        return os.path.join(self.java_home, 'bin', name)


# ------------------------------------------------------------------- helpers

def run(cmd, cfg, label=None, allow_fail=False):
    env = dict(os.environ)
    if cfg.java_home:
        env['JAVA_HOME'] = cfg.java_home
        env['PATH'] = os.path.join(cfg.java_home, 'bin') + os.pathsep + env.get('PATH', '')
    if label:
        print('==> %s' % label)
    r = subprocess.run(cmd, env=env, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    if r.returncode != 0 and not allow_fail:
        print('FAILED: %s' % ' '.join(str(c) for c in cmd))
        if r.stdout.strip():
            print('--- stdout ---\n' + r.stdout[-6000:])
        if r.stderr.strip():
            print('--- stderr ---\n' + r.stderr[-6000:])
        sys.exit(1)
    return r


def aapt2_can_write(cfg, out_dir):
    """Probe whether aapt2 can create its .flat output inside out_dir."""
    probe = os.path.join(out_dir, '_aapt2_probe')
    try:
        if os.path.isdir(probe):
            shutil.rmtree(probe)
        os.makedirs(probe)
    except OSError:
        return False
    sample = None
    for base, _d, files in os.walk(os.path.join(ROOT, 'res')):
        for f in files:
            sample = os.path.join(base, f)
            break
        if sample:
            break
    if not sample:
        return True
    env = dict(os.environ)
    if cfg.java_home:
        env['JAVA_HOME'] = cfg.java_home
    r = subprocess.run([cfg.build_tool('aapt2.exe'), 'compile', '-o', probe, sample],
                       env=env, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    shutil.rmtree(probe, ignore_errors=True)
    return r.returncode == 0


def walk_sources(*dirs):
    out = []
    for d in dirs:
        if not os.path.isdir(d):
            continue
        for base, _dirs, files in os.walk(d):
            for f in files:
                if f.endswith('.java'):
                    out.append(os.path.join(base, f))
    return sorted(out)


def ensure_debug_keystore(cfg):
    """Create a throwaway debug keystore so a fresh clone can build at once."""
    ks = os.path.join(OUT, 'debug.keystore')
    if os.path.isfile(ks):
        return ks, 'android', 'debug'
    os.makedirs(OUT, exist_ok=True)
    keytool = cfg.java('keytool.exe')
    run([keytool, '-genkeypair', '-keystore', ks, '-storetype', 'PKCS12',
         '-storepass', 'android', '-keypass', 'android',
         '-alias', 'debug', '-keyalg', 'RSA', '-keysize', '2048',
         '-validity', '10000', '-dname', 'CN=Android Debug,O=Android,C=US'],
        cfg, label='generate debug keystore')
    return ks, 'android', 'debug'


# ---------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--release', action='store_true',
                    help='label the output as a release build')
    ap.add_argument('--install', action='store_true', help='install via adb')
    ap.add_argument('--sdk')
    ap.add_argument('--java-home', dest='java_home')
    ap.add_argument('--keysote', dest='keystore', help=argparse.SUPPRESS)
    ap.add_argument('--keystore')
    ap.add_argument('--ks-pass', dest='ks_pass')
    ap.add_argument('--ks-alias', dest='ks_alias')
    ap.add_argument('--key', dest='key', help='PKCS#8 PEM private key')
    ap.add_argument('--cert', dest='cert', help='X.509 PEM certificate')
    ap.add_argument('--out', dest='out_dir',
                    help='build output directory (default: <project>/build)')
    ap.add_argument('--serial', help='adb device serial')
    ap.add_argument('--adb', default='adb')
    args = ap.parse_args()

    global OUT
    if args.out_dir:
        OUT = os.path.abspath(args.out_dir)
    elif os.environ.get('STITCH_OUT_DIR'):
        OUT = os.path.abspath(os.environ['STITCH_OUT_DIR'])

    cfg = Config(args)
    cfg.validate()

    # aapt2 cannot create its intermediate .flat files in some directories on
    # Windows ("failed to open file" although ordinary writes succeed). Probe
    # once and fall back automatically so a fresh clone never fails this way.
    if not aapt2_can_write(cfg, OUT):
        fallback = os.path.join(tempfile.gettempdir(), 'subtitlestitch-build')
        if args.out_dir or os.environ.get('STITCH_OUT_DIR'):
            print('WARNING: aapt2 failed its write probe in %s' % OUT)
        elif aapt2_can_write(cfg, fallback):
            print('NOTE: aapt2 cannot write into %s' % os.path.join(ROOT, 'build'))
            print('      falling back to %s' % fallback)
            OUT = fallback
        else:
            print('ERROR: aapt2 cannot create its intermediate files in either')
            print('  %s' % os.path.join(ROOT, 'build'))
            print('  %s' % fallback)
            print('\nThis is a local filesystem restriction, not a project problem.')
            print('Point the build at a directory aapt2 can write to:')
            print('  python build.py --out D:/build/subtitlestitch')
            sys.exit(3)
    print('build output dir: %s' % OUT)

    gen = os.path.join(OUT, 'gen')
    classes = os.path.join(OUT, 'classes')
    dexdir = os.path.join(OUT, 'dex')
    reszip = os.path.join(OUT, 'res.zip')
    unsigned = os.path.join(OUT, 'unsigned.apk')
    aligned = os.path.join(OUT, 'aligned.apk')
    final = os.path.join(OUT, 'SubtitleStitch.apk')

    for d in (gen, classes, dexdir):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)
    # aapt2 compile writes its .flat output here, so create it up front
    os.makedirs(os.path.join(OUT, 'res-compiled'), exist_ok=True)
    for f in (reszip, unsigned, aligned, final):
        if os.path.exists(f):
            os.remove(f)

    # ---------------------------------------------------------- resources
    compiled_res = os.path.join(OUT, 'res-compiled')
    if os.path.isdir(compiled_res):
        shutil.rmtree(compiled_res)
    os.makedirs(compiled_res)

    for base, _dirs, files in os.walk(RES):
        for f in files:
            run([cfg.build_tool('aapt2.exe'), 'compile', '-o', compiled_res,
                 os.path.join(base, f)], cfg)

    flats = sorted(os.path.join(compiled_res, f)
                   for f in os.listdir(compiled_res))
    if not flats:
        print('ERROR: no resources compiled')
        sys.exit(1)

    run([cfg.build_tool('aapt2.exe'), 'link',
         '-o', reszip,
         '-I', cfg.android_jar(),
         '--manifest', MANIFEST,
         '--java', gen,
         '--min-sdk-version', MIN_SDK,
         '--target-sdk-version', TARGET_SDK,
         '--version-code', str(VERSION_CODE),
         '--version-name', VERSION_NAME,
         '--auto-add-overlay'] + flats,
        cfg, label='aapt2 link')

    # --------------------------------------------------------------- java
    sources = walk_sources(SRC, gen)
    if not sources:
        print('ERROR: no java sources')
        sys.exit(1)
    run([cfg.java('javac.exe'), '-source', '17', '-target', '17',
         '-encoding', 'UTF-8', '-nowarn',
         '-classpath', cfg.android_jar(),
         '-d', classes] + sources,
        cfg, label='javac (%d files)' % len(sources))

    # ---------------------------------------------------------------- dex
    class_files = [os.path.join(b, f)
                   for b, _d, fs in os.walk(classes) for f in fs if f.endswith('.class')]
    run([cfg.build_tool('d8.bat'), '--release', '--min-api', MIN_SDK,
         '--lib', cfg.android_jar(), '--output', dexdir] + class_files,
        cfg, label='d8 (%d classes)' % len(class_files))

    classes_dex = os.path.join(dexdir, 'classes.dex')
    if not os.path.isfile(classes_dex):
        print('ERROR: d8 produced no classes.dex')
        sys.exit(1)

    # ------------------------------------------------------------ package
    with zipfile.ZipFile(reszip) as zin, zipfile.ZipFile(unsigned, 'w') as zo:
        for item in zin.infolist():
            if item.filename.startswith('META-INF/'):
                continue
            ct = zipfile.ZIP_STORED if item.filename == 'resources.arsc' \
                else item.compress_type
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = ct
            zi.external_attr = item.external_attr
            zo.writestr(zi, zin.read(item.filename))
        zi = zipfile.ZipInfo('classes.dex', date_time=(2024, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        with open(classes_dex, 'rb') as f:
            zo.writestr(zi, f.read())

    run([cfg.build_tool('zipalign.exe'), '-p', '-f', '4', unsigned, aligned],
        cfg, label='zipalign')

    # --------------------------------------------------------------- sign
    sign_cmd = [cfg.build_tool('apksigner.bat'), 'sign',
                '--min-sdk-version', MIN_SDK,
                '--v1-signing-enabled', 'true',
                '--v2-signing-enabled', 'true',
                '--out', final, aligned]
    if cfg.key_pem and cfg.cert_pem:
        sign_cmd[2:2] = ['--key', cfg.key_pem, '--cert', cfg.cert_pem]
    else:
        if cfg.ks_path and os.path.isfile(cfg.ks_path):
            ks, pw, alias = cfg.ks_path, cfg.ks_pass, cfg.ks_alias
        else:
            ks, pw, alias = ensure_debug_keystore(cfg)
            print('NOTE: no keystore configured, signing with the debug key.')
        sign_cmd[2:2] = ['--ks', ks, '--ks-pass', 'pass:' + pw,
                         '--ks-key-alias', alias, '--key-pass', 'pass:' + pw]
    run(sign_cmd, cfg, label='apksigner')
    run([cfg.build_tool('apksigner.bat'), 'verify', '--print-certs', final],
        cfg, label='verify')

    size = os.path.getsize(final)
    kind = 'release' if args.release else 'debug'
    print('')
    print('BUILD OK (%s): %s (%d bytes, %.2f MB)'
          % (kind, final, size, size / 1048576))

    if args.install:
        adb = [cfg.adb] + (['-s', cfg.serial] if cfg.serial else [])
        run(adb + ['push', final, '/data/local/tmp/stitch.apk'], cfg, label='push')
        r = run(adb + ['shell', 'pm install -r /data/local/tmp/stitch.apk'],
                cfg, label='install')
        print(r.stdout.strip())


if __name__ == '__main__':
    main()
