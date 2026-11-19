# Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause
"""Prove selector validation stays in authoring rather than the client capture loop."""
from copy import deepcopy
import json
from pathlib import Path
import unittest
from lower_inventory_capture import lower_inventory_capture

class InventoryCaptureAuthoringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = Path(__file__).resolve().parents[2] / 'src/test/resources/com/tickreplaylogger/combat_catalog.json'
        cls.references = json.loads(source.read_text(encoding='utf-8'))

    def test_actual_source_projects_without_losing_capture_keys(self):
        source = self.references['observedInventorySourceHints']
        output = lower_inventory_capture(source, self.references['hitsplatRules'])
        self.assertEqual(len(source['rules']), len(output['rules']))
        for before, after in zip(source['rules'], output['rules']):
            self.assertEqual(before['itemIds'], after['ids'])
            self.assertEqual(before['observedHitsplatTypes'], after['hitsplatTypes'])
            for key in ('key', 'name', 'sourceRef', 'pageHash'):
                self.assertEqual(before[key].strip(), after[key])
            self.assertEqual('componentBound' in before, after['needsNumericContext'])

    def test_invalid_authoring_cannot_create_a_runtime_selector(self):
        original = self.references['observedInventorySourceHints']
        mutations = [
            lambda r: r.pop('itemIds'), lambda r: r.pop('observedHitsplatTypes'),
            lambda r: r.pop('sourceRef'), lambda r: r.pop('pageHash'),
            lambda r: r.update(itemIds=[1.5]), lambda r: r.update(itemIds=[True]),
            lambda r: r.update(itemIds=[-1]), lambda r: r.update(itemIds=[1, 1]),
            lambda r: r.update(pageHash='invalid'), lambda r: r.update(sourceRef='unreviewed:source'),
            lambda r: r.update(observedHitsplatTypes=[-1]), lambda r: r.update(quantityPolicy='MULTIPLY'),
            lambda r: r.update(qualification='CERTAIN'), lambda r: r.update(unidentifiedPossible=False),
        ]
        for mutation in mutations:
            namespace = deepcopy(original)
            mutation(namespace['rules'][0])
            with self.subTest(rule=namespace['rules'][0]):
                with self.assertRaises((AssertionError, KeyError)):
                    lower_inventory_capture(namespace, self.references['hitsplatRules'])
        duplicate = deepcopy(original)
        duplicate['rules'].append(deepcopy(duplicate['rules'][0]))
        with self.assertRaises(AssertionError):
            lower_inventory_capture(duplicate, self.references['hitsplatRules'])

if __name__ == '__main__':
    unittest.main()
