#!/usr/bin/env python3
"""Exchange InfoPlist.xcstrings translations with Weblate through native Xcode XLIFF."""

import argparse
import copy
import json
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CATALOG = ROOT / 'iosApp/alphagps/InfoPlist.xcstrings'
DEFAULT_OUTPUT = ROOT / 'localization/ios'
NS = {'x': 'urn:oasis:names:tc:xliff:document:1.2'}


class BridgeError(Exception):
    pass


def language(value):
    if not re.fullmatch(r'[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*', value):
        raise BridgeError(f'Invalid language code: {value!r}')
    return value


def read_xliff(path):
    root = ET.parse(path).getroot()
    if root.tag != f"{{{NS['x']}}}xliff" or root.get('version') != '1.2':
        raise BridgeError(f'{path}: expected XLIFF 1.2')
    files = root.findall('x:file', NS)
    if len(files) != 1 or files[0].get('original') != 'InfoPlist.xcstrings':
        raise BridgeError(f'{path}: expected only the InfoPlist.xcstrings catalog')
    file = files[0]
    source = language(file.get('source-language', ''))
    target = language(file.get('target-language', ''))
    if source == target:
        raise BridgeError(f'{path}: cannot import the source language')
    units = {}
    for unit in file.findall('.//x:trans-unit', NS):
        key = unit.get('id')
        if not key or key in units or unit.find('x:source', NS) is None:
            raise BridgeError(f'{path}: missing source or missing/duplicate unit ID {key!r}')
        units[key] = unit
    if not units:
        raise BridgeError(f'{path}: no translation units')
    return source, target, units


def source_content(element):
    """Ignore serializer whitespace/attributes while retaining inline placeholders."""
    return (
        element.tag,
        element.text or '',
        tuple(sorted((key, value) for key, value in element.attrib.items()
                     if key != '{http://www.w3.org/XML/1998/namespace}space')),
        tuple((source_content(child), child.tail or '') for child in element),
    )


def validate_import(incoming, exported):
    source, target, units = read_xliff(incoming)
    expected_source, expected_target, expected = read_xliff(exported)
    if (source, target) != (expected_source, expected_target):
        raise BridgeError(f'{incoming}: language mismatch')
    for key, unit in units.items():
        target_unit = unit.find('x:target', NS)
        if target_unit is not None and target_unit.get('state') not in (None, 'translated', 'final', 'signed-off'):
            # Xcode silently ignores Weblate's needs-translation targets. Fail
            # visibly so a later export cannot discard an unimported draft.
            raise BridgeError(
                f'{incoming}: {key!r} still needs editing/review '
                f'(state={target_unit.get("state")!r}). Finish the translation '
                'in Weblate before importing; the catalog was not changed.'
            )
        if key not in expected:
            raise BridgeError(f'{incoming}: unknown or removed catalog key {key!r}')
        if source_content(unit.find('x:source', NS)) != source_content(expected[key].find('x:source', NS)):
            raise BridgeError(
                f'{incoming}: source changed for {key!r}. Refresh the XLIFF and review '
                'this translation against the current English text before importing.'
            )


def run_xcode(*args):
    result = subprocess.run(['xcodebuild', *map(str, args)], capture_output=True, text=True)
    if result.returncode:
        raise BridgeError(result.stdout + result.stderr)


class CatalogProject:
    """A resource-only project: no app build, signing, SDK downloads or KMP linking."""

    def __init__(self, directory, catalog, languages):
        self.directory = directory
        self.catalog = directory / 'InfoPlist.xcstrings'
        self.project = directory / 'Catalog.xcodeproj'
        self.project.mkdir()
        self.original = catalog
        source = language(catalog['sourceLanguage'])
        regions = sorted({source, *languages})
        template = Path(__file__).with_name('catalog-project.pbxproj').read_text()
        project = template.replace('__SOURCE_LANGUAGE__', source).replace(
            '__REGIONS__', ', '.join(json.dumps(region) for region in regions)
        )
        (self.project / 'project.pbxproj').write_text(project)
        # This project contains no source code or real Info.plist. Without this
        # temporary setting Xcode considers extracted strings stale and prunes them.
        working = copy.deepcopy(catalog)
        for entry in working['strings'].values():
            entry['extractionState'] = 'manual'
        self.catalog.write_text(json.dumps(working, ensure_ascii=False, indent=2) + '\n')

    def export(self, languages):
        output = self.directory / 'export'
        args = ['-exportLocalizations', '-project', self.project, '-localizationPath', output]
        for locale in languages:
            args += ['-exportLanguage', locale]
        run_xcode(*args)
        return {locale: output / f'{locale}.xcloc/Localized Contents/{locale}.xliff' for locale in languages}

    def import_files(self, paths):
        for path in paths:
            run_xcode('-importLocalizations', '-project', self.project, '-localizationPath', path)
        result = json.loads(self.catalog.read_text())
        if set(result['strings']) != set(self.original['strings']):
            raise BridgeError('Xcode changed the catalog key set; no changes were saved.')
        # Restore extraction metadata from the real catalog after native import.
        for key, entry in result['strings'].items():
            original = self.original['strings'][key]
            if 'extractionState' in original:
                entry['extractionState'] = original['extractionState']
            else:
                entry.pop('extractionState', None)
        return result


def export_catalog(catalog_path, output, languages, replace=False):
    catalog = json.loads(catalog_path.read_text())
    languages = sorted({language(locale) for locale in languages})
    if catalog['sourceLanguage'] in languages:
        raise BridgeError('Export target languages only; English source text is included in each XLIFF.')
    destinations = {locale: output / f'{locale}.xliff' for locale in languages}
    # Fail before running Xcode or replacing ANY file. Weblate may have pending
    # translations that are not yet in the catalog, even in a clean Git checkout.
    existing = [str(path) for path in destinations.values() if path.exists()]
    if existing and not replace:
        raise BridgeError('Will not overwrite existing XLIFF: ' + ', '.join(existing)
                          + '. Import pending translations first; use --replace to refresh explicitly.')
    with tempfile.TemporaryDirectory(prefix='alpha-gps-xliff-') as temporary:
        project = CatalogProject(Path(temporary), catalog, languages)
        exported = project.export(languages)
        contents = {}
        for locale, path in exported.items():
            read_xliff(path)
            contents[destinations[locale]] = path.read_bytes()
        output.mkdir(parents=True, exist_ok=True)
        for path, content in contents.items():
            path.write_bytes(content)
    return list(destinations.values())


def import_catalog(catalog_path, paths):
    before = catalog_path.read_bytes()
    catalog = json.loads(before)
    paths = [path.resolve() for path in paths]
    languages = [read_xliff(path)[1] for path in paths]
    if len(set(languages)) != len(languages):
        raise BridgeError('Import at most one XLIFF file per language.')
    with tempfile.TemporaryDirectory(prefix='alpha-gps-xliff-') as temporary:
        project = CatalogProject(Path(temporary), catalog, languages)
        current = project.export(languages)
        # Validate every file before importing any; a stale translation must not
        # silently replace one whose English source has since been edited.
        for path, locale in zip(paths, languages):
            validate_import(path, current[locale])
        result = project.import_files(paths)
    if catalog_path.read_bytes() != before:
        raise BridgeError('The catalog changed while importing; no changes were saved. Retry.')
    if result != catalog:
        # Match Xcode's JSON formatting. A no-op round trip leaves even bytes alone.
        content = json.dumps(result, ensure_ascii=False, indent=2, separators=(',', ' : ')) + '\n'
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=catalog_path.parent,
                                         prefix='.xliff-import-', delete=False) as temporary:
            temporary.write(content)
            staged = Path(temporary.name)
        staged.chmod(catalog_path.stat().st_mode & 0o777)
        staged.replace(catalog_path)
    return result != catalog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', type=Path, default=DEFAULT_CATALOG)
    commands = parser.add_subparsers(dest='command', required=True)
    export = commands.add_parser('export', help='Export XLIFF for Weblate; does not change the catalog')
    export.add_argument('--language', action='append', required=True)
    export.add_argument('--output-dir', type=Path, default=DEFAULT_OUTPUT)
    export.add_argument('--replace', action='store_true', help='Replace existing XLIFF after importing pending translations')
    importer = commands.add_parser('import', help='Import translated XLIFF into the existing String Catalog')
    importer.add_argument('files', type=Path, nargs='+')
    args = parser.parse_args()
    try:
        if args.command == 'export':
            for path in export_catalog(args.catalog, args.output_dir, args.language, args.replace):
                print(f'Exported {path}')
        else:
            changed = import_catalog(args.catalog, args.files)
            print(f'Updated {args.catalog}' if changed else 'Catalog already matches the translations.')
    except (BridgeError, OSError, ValueError, ET.ParseError) as error:
        parser.exit(1, f'{error}\n')


if __name__ == '__main__':
    main()
