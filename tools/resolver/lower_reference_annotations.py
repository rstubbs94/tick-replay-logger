"""Lower existing reference labels into the shared resolution-rule ABI.

Authoring only: these are annotation effects, never source-admission rules.
No client/network access, runtime matcher, inferred attack, or new reference fact.
"""
from collections import defaultdict
from copy import deepcopy
import hashlib
import json


def condition(at, fact, value):
    return {'at': at, 'fact': fact, 'op': 'equals', 'value': value}


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)


def _integer(value):
    if type(value) is int:
        return value
    if isinstance(value, str) and value.lstrip('-').isdigit() and str(int(value)) == value:
        return int(value)
    return None


def _npc_source(references, npc_id, required_levels=None):
    """Translate the existing baseVariantEstablished/entityRule checks exactly."""
    sensitive = npc_id in references.get('modeSensitiveNpcIds', []) or str(npc_id) in references.get('conditionalNpcIdentities', {})
    levels = None
    if sensitive:
        levels = {_integer(row.get('combat_level')) for row in references.get('npcIdentities', {}).get(str(npc_id), {}).get('candidates', [])}
        levels.discard(None)
    if required_levels is not None:
        required = set(required_levels)
        levels = required if levels is None else levels & required
    if levels is not None and not levels:
        return None
    source = {'kind': 'npc', 'ids': [npc_id]}
    if levels is not None:
        source['combatLevels'] = sorted(levels)
    return source


def _coalesce(rows):
    """Share identical effects across IDs without changing selector qualification."""
    grouped = {}
    for row in rows:
        row = deepcopy(row)
        source = row.get('source', {})
        ids = source.pop('ids', None)
        body = {key: value for key, value in row.items() if key != 'id'}
        key = _canonical(body)
        if key not in grouped:
            grouped[key] = [row, [] if ids is not None else None]
        if ids is not None:
            grouped[key][1].extend(ids)
    result = []
    for row, ids in grouped.values():
        if ids is not None:
            row['source']['ids'] = sorted(set(ids))
        row['id'] = 'annotation:' + hashlib.sha256(_canonical({k: v for k, v in row.items() if k != 'id'}).encode()).hexdigest()[:24]
        result.append(row)
    return sorted(result, key=lambda row: row['id'])


def lower_reference_annotations(references, capture):
    """Return deterministic plain records accepted by the single-IR compiler.

    Merge semantics: select the highest priority within each group, preserving
    consensus at a tied priority. Fold base_reference before component_reference.
    Hint groups append in NPC, asset, component order. Final generic merge omits
    union types and clears timing for typeless/NONE_REVIEWED sources. Existing
    component annotations and source combat level are already-bound row facts,
    copied by that generic fold rather than rematched by this authoring module.
    """
    rows = []

    def emit(domain, annotations, *, source=None, signals=None, when=(), group, priority=0, mode='consensus'):
        order = {'base_reference': 0, 'source_prayer_reference': 10, 'timing_reference': 20,
                 'player_action': 30, 'npc_style_hint': 40, 'asset_style_hint': 50, 'hit_reference': 0}[group]
        row = {'id': '', 'role': 'annotation', 'domain': domain,
               'when': {'all': list(when)}, 'effects': {'annotations': annotations},
               'merge': {'group': group, 'priority': priority, 'mode': mode, 'order': order}}
        if source is not None:
            row['source'] = deepcopy(source)
        if signals is not None:
            row['signals'] = deepcopy(signals)
        rows.append(row)

    def flag(name):
        return condition('cue', name, True)

    def signal(kind, identifier):
        # Legacy single() never labelled a list containing several signals.
        return {kind: [identifier]}, condition('cue', 'signals.' + kind, [identifier])

    static = flag('staticReferenceEligible')
    for identifier, rule in references.get('hitsplatRules', {}).get('rules', {}).items():
        annotations = {}
        if rule.get('prayerEffect') is not None:
            annotations.update(prayerEffect=rule['prayerEffect'].upper() + '_REVIEWED', damagePropertyEvidence='SOURCE_REFERENCE')
        if rule.get('referenceKey') is not None:
            annotations['damageReferenceKey'] = rule['referenceKey']
        family = rule.get('damageType')
        if family in ('poison', 'venom', 'burn', 'bleed'):
            annotations.update(damageType=family, damageTypeEvidence='HITSPLAT_TYPE', attackerName=family.capitalize())
        if annotations:
            emit('hit', annotations, when=[condition('hit', 'hitsplatType', int(identifier))], group='hit_reference')

    for animation, style in capture.get('playerAnimationStyles', {}).items():
        signals, single = signal('animationIds', int(animation))
        emit('cue', {'playerActionStyle': style}, signals=signals,
             when=[flag('animationReferenceEligible'), single], group='player_action')

    for npc, attacks in capture.get('npcAttackRules', {}).items():
        source = _npc_source(references, int(npc))
        if source is None:
            continue
        for animation, rule in attacks.items():
            signals, single = signal('animationIds', int(animation))
            annotations = {'damageTypeEvidence': 'LABELLED_FROM_MAP'}
            for field, original in [('damageType', 'damageType'), ('attackStyleName', 'name'), ('mapRef', 'mapRef')]:
                if rule.get(original) is not None:
                    annotations[field] = rule[original]
            # Current reviewed attack declarations all identify a type. Reject a
            # changed authoring shape rather than silently altering fallbacks.
            if rule.get('damageType') is None:
                raise ValueError('Attack annotation lacks its existing type: ' + npc + '/' + animation)
            emit('cue', annotations, source=source, signals=signals,
                 when=[static, flag('actorAnimationObserved'), single], group='base_reference', priority=30)

    projectile_matches = defaultdict(list)
    bindings = references.get('reviewedBindings', {})
    for rule in bindings.get('projectileStyleRules', []):
        for npc in rule.get('npcIds', []):
            for projectile, style in rule.get('styles', {}).items():
                projectile_matches[(npc, int(projectile))].append((style, rule.get('key')))
    for (npc, projectile), matches in sorted(projectile_matches.items()):
        source = _npc_source(references, npc)
        styles = {style for style, _ in matches}
        if source is None or len(styles) != 1:
            continue
        style = next(iter(styles))
        signals, single = signal('projectileIds', projectile)
        annotations = {'damageType': style, 'damageTypeEvidence': 'SOURCE_REFERENCE'}
        # Equal type declarations can agree, but the legacy key requires one
        # matching declaration, not merely one distinct key string.
        if len(matches) == 1 and matches[0][1] is not None:
            annotations.update(damageTypeRuleKey=matches[0][1], damageTypeCatalogId=references.get('catalogId'))
        emit('cue', annotations, source=source, signals=signals,
             when=[static, single], group='base_reference', priority=20)

    timing_matches = defaultdict(list)
    for rule in bindings.get('prayerTimingRules', []):
        for npc in rule.get('npcIds', []):
            for projectile in rule.get('projectileIds', []):
                timing_matches[(npc, projectile)].append(rule)
    for (npc, projectile), matches in sorted(timing_matches.items()):
        source = _npc_source(references, npc)
        if source is None or len(matches) != 1 or matches[0].get('key') is None:
            continue
        rule = matches[0]
        if rule.get('determination') in (None, 'UNKNOWN'):
            continue
        signals, single = signal('projectileIds', projectile)
        emit('cue', {'determination': rule['determination'], 'determinationRuleKey': rule['key']},
             source=source, signals=signals, when=[static, single], group='timing_reference')

    for npc, rule in references.get('resolvedEntityRules', {}).items():
        source = _npc_source(references, int(npc), rule.get('requiredCombatLevels'))
        if source is None:
            continue
        if rule.get('damageType') is not None:
            annotations = {'damageType': rule['damageType'], 'damageTypeEvidence': 'SCOPED_DEFINITION_RULE',
                           'mapRef': 'replay_bindings.json:' + str(rule.get('key'))}
            emit('cue', annotations, source=source, when=[static], group='base_reference', priority=10)
            emit('cue', {'candidateStyles': [rule['damageType']]}, source=source, when=[static],
                 group='npc_style_hint', priority=1, mode='append_unique')
        if rule.get('prayerEffect') == 'none':
            emit('cue', {'prayerEffect': 'NONE_REVIEWED'}, source=source, when=[static], group='source_prayer_reference')

    for npc, styles in capture.get('npcStyles', {}).items():
        source = _npc_source(references, int(npc))
        if source is not None and len(styles) == 1:
            emit('cue', {'candidateStyles': styles}, source=source, when=[static],
                 group='npc_style_hint', priority=0, mode='append_unique')

    for projectile, style in capture.get('projectileStyles', {}).items():
        signals, single = signal('projectileIds', int(projectile))
        emit('cue', {'candidateStyles': [style]}, signals=signals,
             when=[flag('symbolReferenceEligible'), single], group='asset_style_hint', mode='append_unique')

    hazards = defaultdict(list)
    for family in ('objectRules', 'graphicRules', 'npcHazardRules'):
        for rule in bindings.get(family, {}).values():
            if rule.get('damaging') is True and isinstance(rule.get('name'), str):
                hazards[rule['name']].append(rule.get('damageType'))
    for name, types in sorted(hazards.items()):
        if any(not isinstance(value, str) or not value for value in types) or len(set(types)) != 1:
            continue
        annotations = {'damageType': types[0], 'damageTypeEvidence': 'SOURCE_REFERENCE',
                       'mapRef': 'replay_bindings.json:hazard_damage_type'}
        # Row label is preferred; an unavailable/conflicting label has no
        # emitted record, so the original source-name fallback remains usable.
        for at, fact, priority in [('source', 'name', 0), ('row', 'label', 1)]:
            emit('cue', annotations, when=[flag('hazardReferenceEligible'), condition(at, fact, name)],
                 group='base_reference', priority=priority)

    return _coalesce(rows)
