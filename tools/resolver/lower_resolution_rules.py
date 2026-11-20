"""Lossless authoring projection into the single resolution-rule namespace.

This module performs no runtime interpretation. Every original declaration field
and selector is retained; only the shared role/index fields are added.
"""
from copy import deepcopy
import hashlib
import json


TABLES = (
    ('ordinaryProfiles', 'component'),
    ('specialRules', 'component'),
    ('activityClassifications', 'activity'),
    ('sourceBounds', 'source_bound'),
    ('sourceAssociations', 'presence'),
    ('qualifiedLimits', 'qualified_limit'),
    ('evidenceRules', 'evidence'),
    ('inputLimits', 'input_limit'),
    ('contactPolicies', 'contact_limit'),
)


def _source(selector):
    kinds = {'ITEM': 'item', 'OBJECT': 'object', 'NPC_ORDINARY_ATTACK': 'npc', 'NPC_PROJECTILE_COMPONENT': 'npc'}
    kind = selector.get('kind')
    if kind not in kinds:
        raise ValueError('Untranslated source selector kind: ' + str(kind))
    result = {'kind': kinds[kind]}
    if 'ids' in selector:
        result['ids'] = deepcopy(selector['ids'])
    if 'observedNames' in selector:
        result['names'] = deepcopy(selector['observedNames'])
    return result


def lower_resolution_rules(resolver):
    result = []

    def append(original, role, profile=None):
        row = deepcopy(original)
        if role == 'evidence' and row.get('evidenceMode') == 'SYSTEM_RESPONSE':
            row['admissionMaximum'] = deepcopy(row['declaration']['admissionMaximum'])
        # Item possession has the same per-callback bound operation as other
        # evidence families; quantity is deliberately never a multiplier.
        if role == 'evidence' and row.get('evidenceMode') == 'INVENTORY_POSSESSION':
            bound = row.get('declaration', {}).get('componentBound')
            if bound:
                if bound.get('numericScope') != 'PER_CALLBACK_MAXIMUM':
                    raise ValueError('Untranslated possession bound scope')
                row['perHitMaximum'] = deepcopy(bound['maximum'])
                row['maximumContextPredicate'] = deepcopy(bound['contextPredicate'])
        if 'role' in row:
            if role != 'activity' or 'classificationRole' in row:
                raise ValueError('Untranslated pre-existing role field: ' + str(row.get('id', row.get('key'))))
            row['classificationRole'] = row['role']
        row['role'] = role
        if profile is not None:
            if 'profile' in row:
                raise ValueError('Pre-existing profile field: ' + str(row.get('id')))
            row['profile'] = profile
        if role in ('input_limit', 'outcome'):
            normalized = _source(row['selector'])
            if 'source' in row and row['source'] != normalized:
                raise ValueError('Conflicting normalized source: ' + str(row.get('key')))
            row['source'] = normalized
        if not row.get('id'):
            row['id'] = row.get('key')
        if not row.get('id'):
            raw = json.dumps(original, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
            row['id'] = role + ':' + hashlib.sha256(raw).hexdigest()
        result.append(row)

    for table, role in TABLES:
        for row in resolver.get(table, []):
            append(row, role, table == 'ordinaryProfiles' if role == 'component' else None)
    for row in resolver.get('outcomeBindings', {}).get('rules', []):
        append(row, 'outcome')
    identifiers = [row['id'] for row in result]
    if len(identifiers) != len(set(identifiers)):
        duplicates = sorted({key for key in identifiers if identifiers.count(key) > 1})
        raise ValueError('Duplicate resolution rule IDs: ' + ', '.join(duplicates))
    return result


def resolution_metadata(resolver):
    """Keep every non-rule outcome reference fact without a second interpreter."""
    parent = deepcopy(resolver.get('outcomeBindings', {}))
    parent.pop('rules', None)
    result = {'outcomeMetadata': parent}
    if 'knownHitsplatTypes' in parent:
        result['knownHitsplatTypes'] = parent.pop('knownHitsplatTypes')
    return result
