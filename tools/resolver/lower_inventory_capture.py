# Copyright (c) 2026, rstubbs94. SPDX-License-Identifier: BSD-2-Clause
"""Validate authored inventory selectors and retain only capture lookup facts.

No callback, clock, spatial, attribution, or damage-bound decisions execute here.
"""
from copy import deepcopy
import re

FIELDS = {'key', 'name', 'itemIds', 'referenceKeys', 'sourceRef', 'pageHash',
          'observationKind', 'mechanism', 'quantityPolicy', 'qualification',
          'unidentifiedPossible', 'observedOutcomeDomain', 'observedHitsplatTypes', 'provenance'}

def lower_inventory_capture(namespace, hitsplat_rules):
    assert set(namespace) == {'version', 'rules'} and type(namespace['version']) is int and namespace['version'] == 1
    assert isinstance(namespace['rules'], list) and len(namespace['rules']) <= 4096
    positive = {int(identifier) for identifier, row in hitsplat_rules['rules'].items()
                if row['name'] in {'DAMAGE_ME', 'DAMAGE_OTHER', 'DAMAGE_MAX_ME'}}
    assert len(positive) == 3
    keys, bound_ids, output = set(), set(), []
    for row in namespace['rules']:
        assert set(row) in (FIELDS, FIELDS | {'componentBound'})
        strings = {}
        for name in ('key', 'name', 'sourceRef', 'pageHash'):
            assert isinstance(row[name], str) and row[name].strip()
            strings[name] = row[name].strip()
        assert strings['key'] not in keys
        assert strings['sourceRef'].startswith('wiki:') and re.fullmatch('[0-9a-f]{64}', strings['pageHash'])
        ids, outcomes = row['itemIds'], row['observedHitsplatTypes']
        for values in (ids, outcomes):
            assert isinstance(values, list) and 0 < len(values) <= 4096
            assert all(type(value) is int and 0 <= value <= 2147483647 for value in values)
            assert len(set(values)) == len(values)
        assert set(outcomes) <= positive
        assert not bound_ids.intersection(ids) and len(bound_ids) + len(ids) <= 4096
        expected = {'observationKind': 'LOCAL_INVENTORY_PRESENCE_AT_HP_CALLBACK', 'mechanism': 'ITEM_POSSESSION',
                    'quantityPolicy': 'PRESENCE_ONLY_NO_MULTIPLIER', 'qualification': 'POSSIBLE_SOURCE_HINT',
                    'observedOutcomeDomain': 'ORDINARY_HP_CALLBACK_ONLY'}
        assert all(row[name] == value for name, value in expected.items())
        assert row['unidentifiedPossible'] is True
        keys.add(strings['key']); bound_ids.update(ids)
        output.append({**strings, 'ids': deepcopy(ids), 'hitsplatTypes': deepcopy(outcomes),
                       'needsNumericContext': 'componentBound' in row})
    return {'schemaVersion': 1, 'rules': output}
