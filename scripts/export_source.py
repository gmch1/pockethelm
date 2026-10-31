#!/usr/bin/env python3
"""Export a source-only review archive using an explicit allowlist, not the working directory."""
import argparse
from pathlib import Path
import re
import tarfile

ROOT = Path(__file__).resolve().parent.parent
FILES = ('.gitignore', 'README.md', 'SECURITY.md', 'android/build.gradle.kts',
    'android/settings.gradle.kts', 'android/gradle.properties', 'android/gradlew', 'android/gradlew.bat',
    'android/app/build.gradle.kts')
TREES = ('android/app/src', 'android/gradle/wrapper', 'scripts', 'config')
SUFFIXES = {'.py', '.txt', '.kt', '.xml', '.properties', '.jar', '.json'}


def source_files():
    files = [ROOT / name for name in FILES]
    for name in TREES:
        files.extend(p for p in (ROOT/name).rglob('*') if p.is_file() and p.suffix in SUFFIXES)
    for p in sorted(set(files)):
        if p.is_symlink() or ROOT not in p.resolve().parents: raise ValueError('Symlink/outside source file rejected')
        if '__pycache__' in p.parts: continue
        relative = p.relative_to(ROOT).as_posix()
        if relative.startswith('config/') and '.example.' not in p.name: continue
        if p.suffix == '.jar' and relative != 'android/gradle/wrapper/gradle-wrapper.jar':
            raise ValueError('Unexpected binary in source export')
        if p.suffix != '.jar':
            text = p.read_text()
            if re.search(r'-----BEGIN (?:OPENSSH |RSA |EC )?PRIVATE KEY-----\s*\n[A-Za-z0-9+/=]{20,}', text):
                raise ValueError('Private key material rejected: '+relative)
            if re.search(r'codex-remote:pair:[A-Za-z0-9_-]{100,}', text):
                raise ValueError('Pairing credential rejected: '+relative)
        yield p, relative


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args();out=args.out.expanduser().resolve()
    if out==ROOT or ROOT in out.parents:parser.error('Write the review archive outside the working directory')
    files=list(source_files());out.parent.mkdir(parents=True,exist_ok=True)
    with tarfile.open(out,'w:gz') as archive:
        for path,relative in files:archive.add(path,arcname='pockethelm/'+relative,recursive=False)
    print('Source review archive:',out,'—',len(files),'files; no deployment/artifact/signing directories included')

if __name__=='__main__':main()
