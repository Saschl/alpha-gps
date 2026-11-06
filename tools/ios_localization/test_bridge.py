import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from bridge import BridgeError, NS, export_catalog, import_catalog, read_xliff, validate_import

CATALOG = {
    'sourceLanguage': 'en',
    'strings': {
        'CFBundleDisplayName': {
            'extractionState': 'extracted_with_value',
            'localizations': {'en': {'stringUnit': {'state': 'new', 'value': 'Alpha GPS'}}},
        },
        'NSLocationWhenInUseUsageDescription': {
            'comment': 'Location access while using the app.',
            'extractionState': 'extracted_with_value',
            'localizations': {
                'en': {'stringUnit': {'state': 'new', 'value': 'Send location & time to "your camera".'}},
                'de': {'stringUnit': {'state': 'translated', 'value': 'Standort & Zeit an „deine Kamera“ senden.'}},
            },
        },
    },
    'version': '1.2',
}
KEY = 'NSLocationWhenInUseUsageDescription'
ET.register_namespace('', NS['x'])


def edit_translation(path, text, state='translated'):
    tree = ET.parse(path)
    unit = tree.find(f'.//x:trans-unit[@id="{KEY}"]', NS)
    target = unit.find('x:target', NS)
    if target is None:
        target = ET.SubElement(unit, f"{{{NS['x']}}}target")
    target.text = text
    target.attrib = {'state': state} if state else {}
    tree.write(path, encoding='utf-8', xml_declaration=True)


class GuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.catalog = self.root / 'InfoPlist.xcstrings'
        self.catalog.write_text(json.dumps(CATALOG, ensure_ascii=False))

    def xliff(self, source='Current English', original='InfoPlist.xcstrings', units=None):
        path = self.root / 'de.xliff'
        body = units or f'<trans-unit id="key"><source>{source}</source><target>Deutsch</target></trans-unit>'
        path.write_text(f'<xliff xmlns="{NS["x"]}" version="1.2"><file original="{original}" '
                        f'source-language="en" target-language="de"><body>{body}</body></file></xliff>')
        return path

    def test_export_refuses_to_overwrite_pending_translations_before_running_xcode(self):
        incoming = self.xliff()
        before = incoming.read_bytes()
        with patch('bridge.run_xcode') as xcode:
            with self.assertRaisesRegex(BridgeError, 'overwrite existing XLIFF'):
                export_catalog(self.catalog, self.root, ['de', 'fr'])
            xcode.assert_not_called()
        self.assertEqual(before, incoming.read_bytes())
        self.assertFalse((self.root / 'fr.xliff').exists())

    def test_stale_source_rejected(self):
        path = self.xliff('Old English')
        current = self.root / 'current.xliff'
        current.write_text(path.read_text().replace('Old English', 'Current English'))
        with self.assertRaisesRegex(BridgeError, 'source changed'):
            validate_import(path, current)

    def test_unknown_key_and_unrelated_file_rejected(self):
        path = self.xliff()
        current = self.root / 'current.xliff'
        current.write_text(path.read_text().replace('id="key"', 'id="other"'))
        with self.assertRaisesRegex(BridgeError, 'unknown or removed'):
            validate_import(path, current)
        self.xliff(original='Other.xcstrings')
        with self.assertRaisesRegex(BridgeError, 'expected only'):
            read_xliff(path)

    def test_duplicate_keys_rejected(self):
        path = self.xliff(units='<trans-unit id="key"><source>One</source></trans-unit>' * 2)
        with self.assertRaisesRegex(BridgeError, 'duplicate'):
            read_xliff(path)

    def test_failed_import_leaves_original_catalog_untouched(self):
        path = self.xliff()
        before = self.catalog.read_bytes()
        with patch('bridge.CatalogProject.export', return_value={'de': path}), \
                patch('bridge.CatalogProject.import_files', side_effect=BridgeError('Xcode failed')):
            with self.assertRaisesRegex(BridgeError, 'Xcode failed'):
                import_catalog(self.catalog, [path])
        self.assertEqual(before, self.catalog.read_bytes())


@unittest.skipUnless(os.environ.get('RUN_XCODE_LOCALIZATION_TESTS') == '1', 'requires Xcode on macOS')
class XcodeRoundTripTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.catalog = self.root / 'InfoPlist.xcstrings'
        self.catalog.write_text(json.dumps(CATALOG, ensure_ascii=False, indent=2) + '\n')
        self.output = self.root / 'xliff'

    def test_noop_round_trip_keeps_bytes_metadata_untranslated_keys_and_comments(self):
        before = self.catalog.read_bytes()
        paths = export_catalog(self.catalog, self.output, ['de'])
        self.assertEqual(before, self.catalog.read_bytes())
        _, _, units = read_xliff(paths[0])
        self.assertEqual(set(CATALOG['strings']), set(units))
        self.assertEqual(CATALOG['strings'][KEY]['comment'], units[KEY].find('x:note', NS).text)
        self.assertFalse(import_catalog(self.catalog, paths))
        self.assertEqual(before, self.catalog.read_bytes())
        self.assertEqual([], list(self.root.rglob('*.strings')))

    def test_weblate_target_without_state_imports_and_preserves_other_catalog_fields(self):
        paths = export_catalog(self.catalog, self.output, ['de'])
        translation = 'Neue Übersetzung: „GPS & Uhrzeit“ 📷'
        # Weblate may omit state/approved attributes for an ordinary translation.
        edit_translation(paths[0], translation, state=None)
        self.assertTrue(import_catalog(self.catalog, paths))
        expected = copy.deepcopy(CATALOG)
        expected['strings'][KEY]['localizations']['de']['stringUnit']['value'] = translation
        self.assertEqual(expected, json.loads(self.catalog.read_text()))
        self.assertFalse(import_catalog(self.catalog, paths))

    def test_new_language_preserves_german_and_leaves_missing_targets_untranslated(self):
        paths = export_catalog(self.catalog, self.output, ['fr'])
        edit_translation(paths[0], 'Envoyer la position à votre appareil photo.')
        self.assertTrue(import_catalog(self.catalog, paths))
        result = json.loads(self.catalog.read_text())
        self.assertEqual(CATALOG['strings'][KEY]['localizations']['de'], result['strings'][KEY]['localizations']['de'])
        self.assertEqual('translated', result['strings'][KEY]['localizations']['fr']['stringUnit']['state'])
        self.assertNotIn('fr', result['strings']['CFBundleDisplayName']['localizations'])

    def test_weblate_draft_is_rejected_instead_of_silently_lost_by_xcode(self):
        paths = export_catalog(self.catalog, self.output, ['de'])
        edit_translation(paths[0], 'Entwurf zur Prüfung', state='needs-translation')
        before = self.catalog.read_bytes()
        with self.assertRaisesRegex(BridgeError, 'still needs editing/review'):
            import_catalog(self.catalog, paths)
        self.assertEqual(before, self.catalog.read_bytes())

    def test_source_edit_blocks_old_translation_without_changing_catalog(self):
        paths = export_catalog(self.catalog, self.output, ['de'])
        changed = copy.deepcopy(CATALOG)
        changed['strings'][KEY]['localizations']['en']['stringUnit']['value'] = 'Different English'
        self.catalog.write_text(json.dumps(changed))
        before = self.catalog.read_bytes()
        with self.assertRaisesRegex(BridgeError, 'source changed'):
            import_catalog(self.catalog, paths)
        self.assertEqual(before, self.catalog.read_bytes())


if __name__ == '__main__':
    unittest.main()
