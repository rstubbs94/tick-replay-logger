"""Project the frozen production references into one inspectable runtime package.

Authoring tool only. No game/network access and no code generation into Java.
Frozen authoring/test inputs retain historical sections; the runtime package contains
only the factual sections consumed by capture and the current resolver.
"""
import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
from lower_resolution_rules import lower_resolution_rules, resolution_metadata, TABLES
from lower_reference_annotations import lower_reference_annotations
from lower_inventory_capture import lower_inventory_capture

BASELINE = '1505ed1653c8b7996a3880fb3aada99a41bb73b8'
BASE = 'src/main/resources/com/tickreplaylogger/'

# CombatCatalog's factual lookups and catalog lineage. Historical constraint and
# mechanism tables have already been lowered into resolver.resolutionRules.
RUNTIME_REFERENCE_KEYS = {
    'schemaVersion', 'catalogId', 'runtimeProjection', 'npcIdentities',
    'npcDefinitionStates', 'reviewedBindings', 'entityRoleReferences',
    'contextBindings', 'inventorySourceCapture', 'selfDamageInputs',
    'conditionalNpcIdentities', 'modeSensitiveNpcIds', 'hitsplatRules', 'symbols',
    'observationReferences', 'attackReferencesByNpcId', 'attackReferencesByPage',
}
RUNTIME_CAPTURE_KEYS = {'version', 'npcAttackSpeeds', 'hazardNamesByKind', 'objectHazardRules'}

def read_baseline(repo, name, input_dir=None):
    # Committed frozen authoring inputs: no git history, game client or network is required.
    directory = input_dir or repo / 'src/test/resources/com/tickreplaylogger'
    raw = (directory / name).read_bytes()
    return json.loads(raw), hashlib.sha256(raw).hexdigest()

def constant(expression):
    if expression.get('op') == 'CONST' and type(expression.get('value')) is int:
        return expression['value']
    if expression.get('op') == 'MAX' and expression.get('args'):
        return max(constant(value) for value in expression['args'])
    raise ValueError('Untranslated numeric operator: ' + str(expression.get('op')))

def predicates(expression):
    if not expression:
        return []
    if expression.get('op') == 'ALL':
        return [condition for value in expression['args'] for condition in predicates(value)]
    if expression.get('op') == 'EQ':
        left, right = expression['left'], expression['right']
        if left.get('op') == 'CONST':
            left, right = right, left
        if left.get('op') == 'FACT' and right.get('op') == 'CONST':
            return [{'fact': left['name'], 'value': right['value']}]
    raise ValueError('Untranslated predicate operator: ' + str(expression.get('op')))

def whole_witness(witness):
    if set(witness) != {'directHpComponents', 'componentSetEvidence', 'modifierEnvelope', 'excludedOutcomeKinds'}:
        return False
    modifiers = witness['modifierEnvelope']
    components = witness['directHpComponents']
    included = modifiers.get('includedStates', [])
    return (witness['componentSetEvidence'] == 'EXHAUSTIVE_PRIMARY_SOURCE_SET'
        and 0 < len(components) <= 256 and len(set(components)) == len(components)
        and len(witness['excludedOutcomeKinds']) == 6
        and set(witness['excludedOutcomeKinds']) == {'STATUS', 'OTHER_ACTOR', 'ENVIRONMENT_CONTACT', 'SELF', 'REFLECTED', 'NON_HP'}
        and set(modifiers) == {'scope', 'includedStates', 'excludedStates'}
        and modifiers['scope'] == 'SOURCE_VARIANT_ALL_DIRECT_HITS'
        and 0 < len(included) <= 256 and len(set(included)) == len(included)
        and len(modifiers['excludedStates']) == 2
        and set(modifiers['excludedStates']) == {'NON_NORMAL_WORLD_CONTEXT', 'MODEL_OVERRIDES'})

def limits(references):
    result = []
    whole_types = {int(key) for key, value in references['hitsplatRules']['rules'].items()
        if value['name'] in {'DAMAGE_ME', 'DAMAGE_OTHER', 'DAMAGE_MAX_ME', 'BLOCK_ME', 'BLOCK_OTHER'}}
    assert len(whole_types) == 5
    for original in references['attributionConstraints']['rules']:
        selector = original['selector']
        independent = original.get('composition') == 'INDEPENDENT_COMPONENT_GUARD'
        whole = original.get('boundBasis') == 'QUALIFIED_WHOLE_ACTOR_ENVELOPE'
        conditional = 'maximumExpression' in original or 'conditions' in original or independent
        missing, when = [], []
        def require(at, fact, value, reason, op='equals'):
            when.append({'at': at, 'fact': fact, 'op': op, 'value': value, 'reason': reason})
        basis = original.get('boundBasis')
        if basis is not None and basis not in ('VARIANT_LITERAL_MAXIMUM', 'QUALIFIED_MECHANIC_EXPRESSION', 'QUALIFIED_WHOLE_ACTOR_ENVELOPE'):
            missing.append('BOUND_BASIS_UNAVAILABLE')
        if conditional:
            if original.get('numericScope') != 'PER_HIT_MAXIMUM':
                missing.append('NUMERIC_SCOPE_NOT_QUALIFIED')
            if not all(original.get(key) if key == 'familyKey' else selector.get(key)
                    for key in ('familyKey', 'mechanism', 'damageComponent', 'worldMode')):
                missing.append('CONDITIONAL_BOUND_SELECTOR_UNAVAILABLE')
        if original.get('composition') not in (None, 'ALTERNATIVE_CASE', 'INDEPENDENT_COMPONENT_GUARD'):
            missing.append('BOUND_COMPOSITION_UNESTABLISHED')
        if independent:
            if 'maximumExpression' not in original or 'conditions' not in original:
                missing.append('INDEPENDENT_COMPONENT_QUALIFICATION_UNAVAILABLE')
            required = {'QUALIFIED_NPC_VARIANT', 'NORMAL_WORLD_CONTEXT', 'NO_MODEL_OVERRIDES_OBSERVED',
                'SINGLE_HIT_COMPONENT', 'NPC_DIRECT_SOURCE_HYPOTHESIS' if whole else 'ORDINARY_ATTACK_COMPONENT'}
            if not required <= set(original.get('requiredObservations', [])):
                missing.append('INDEPENDENT_COMPONENT_OBSERVATIONS_UNAVAILABLE')
        if whole:
            if (not independent or not whole_witness(original.get('wholeActorWitness', {}))
                    or selector.get('mechanism') != 'ALL_DIRECT_HP_COMPONENTS'
                    or selector.get('damageComponent') != 'SINGLE_HIT' or selector.get('worldMode') != 'NORMAL'):
                missing.append('WHOLE_ACTOR_ENVELOPE_SCOPE_UNESTABLISHED')
            types = original.get('allowedHitsplatTypes', [])
            if not types or len(set(types)) != len(types) or not set(types) <= whole_types:
                missing.append('WHOLE_ACTOR_HITSPLAT_APPLICABILITY_UNESTABLISHED')
            require('observations', 'NPC_DIRECT_SOURCE_HYPOTHESIS', True, 'NPC_DIRECT_SOURCE_HYPOTHESIS')
            require('cue', 'mechanism', ['ORDINARY_ATTACK', 'NPC_DIRECT_SOURCE'], 'NPC_DIRECT_SOURCE_HYPOTHESIS', 'one_of')
            require('hit', 'hitsplatType', types, 'WHOLE_ACTOR_HITSPLAT_APPLICABILITY_UNESTABLISHED', 'one_of')
        envelope = original.get('envelopePolicy', {})
        envelope_known = (envelope.get('operator') == 'BASE_PROFILE_ENVELOPE'
            and envelope.get('evidence') == 'SOURCE_RELATIVE_ORDINARY_BASE_PROFILE'
            and envelope.get('knownExceptionalContext') == 'BLOCK_BASE_BRANCH_RETAIN_ALTERNATIVES'
            and isinstance(envelope.get('blockedActivities'), list)
            and all(isinstance(value, str) for value in envelope['blockedActivities']))
        if envelope_known:
            require('observations', 'STANDARD_DAMAGE_MODIFIERS', True, 'STANDARD_DAMAGE_MODIFIERS')
            require('cue', 'observedAttackStyle', original.get('attackStyles', []), 'BASE_PROFILE_ATTACK_STYLE_NOT_QUALIFIED', 'one_of')
        for condition in predicates(original.get('conditions')):
            require('numericFacts', condition['fact'], condition['value'], 'BOUND_CONDITION_UNESTABLISHED')
        if original.get('familyKey') is not None:
            require('source', 'familyKey', original['familyKey'], 'SOURCE_FAMILY_NOT_QUALIFIED')
        if not whole:
            require('cue', 'mechanism', selector.get('mechanism'), 'COMPONENT_OR_MODE_NOT_QUALIFIED')
        for key in ('damageComponent', 'worldMode'):
            require('cue', key, selector.get(key), 'COMPONENT_OR_MODE_NOT_QUALIFIED')
        if not original.get('requiredObservations'):
            missing.append('REQUIRED_OBSERVATIONS_UNAVAILABLE')
        for required in original.get('requiredObservations', []):
            require('observations', required, True, required)
        row = {'id': original['key'], 'source': {'kind': 'npc', 'ids': selector['npcIds']},
            'maximum': constant(original['maximumExpression']) if 'maximumExpression' in original else original.get('maximum'),
            'authority': 'verified' if original.get('qualification') == 'HARD_BOUND' and not missing else 'reference_only',
            'composition': 'independent_guard' if independent else 'alternative_case',
            'when': when, 'staticMissing': missing,
            'exclusionScope': 'ALL_DIRECT_HP_COMPONENTS' if whole else 'ORDINARY_ATTACK'
                if selector.get('mechanism') == 'ORDINARY_ATTACK' else None,
            'referenceKey': original['key']}
        if envelope_known:
            row['envelopePolicy'] = deepcopy(envelope)
        result.append(row)
    return result


def geometry(value, source_kind, endpoint=False):
    operator = value['operator']
    if operator in ('TILE', 'EXACT_RECORDED_DESTINATION_TILE'):
        return {'shape': 'point', 'anchor': 'projectile_endpoint' if endpoint else 'effect_position', 'authority': 'reference_only'}
    if operator in ('CHEBYSHEV_RADIUS', 'OBSERVED_API_OCCUPIED_BOUNDS_PROXIMITY'):
        result = {'shape': 'radius', 'anchor': 'source_bounds' if 'BOUNDS' in operator else 'source_position',
            'radiusTiles': value.get('radius', value.get('maxChebyshevTiles')), 'metric': 'chebyshev', 'authority': 'reference_only'}
        if 'minChebyshevTiles' in value:
            result['minimumRadiusTiles'] = value['minChebyshevTiles']
        return result
    if operator == 'OBSERVED_API_OCCUPIED_BOUNDS':
        return {'shape': 'occupied_bounds', 'anchor': 'source_bounds', 'authority': 'reference_only'}
    raise ValueError('Untranslated footprint: ' + operator)

def evidence_rules(references):
    result = []
    families = [('observedInventorySourceHints', 'INVENTORY_POSSESSION', 'item', 'itemIds'),
        ('observedSelfDamageTextHints', 'SYSTEM_RESPONSE', 'item', 'itemIds'),
        ('observedProjectileEndpointHints', 'ENDPOINT_COOCCURRENCE', 'npc', 'sourceNpcIds'),
        ('observedNpcContactHints', 'ORIGINAL_CONTACT', 'npc', 'npcIds'),
        ('observedGraphicCues', 'ORIGINAL_CONTACT', 'graphic', 'graphicIds')]
    for section, mode, kind, field in families:
        for original in references[section]['rules']:
            row = {'id': original['key'], 'evidenceMode': mode,
                'source': {'kind': kind, 'ids': original[field]}, 'declaration': deepcopy(original),
                'label': original.get('name'), 'causalAssociationOnly': True,
                'evidencePrefix': {'INVENTORY_POSSESSION':'ITEM_POSSESSION_SOURCE_RULE:',
                    'SYSTEM_RESPONSE':'SELF_SYSTEM_RESPONSE_RULE:', 'ENDPOINT_COOCCURRENCE':'PROJECTILE_ENDPOINT_SOURCE_RULE:',
                    'ORIGINAL_CONTACT':'GRAPHIC_CUE_RULE:' if section=='observedGraphicCues' else
                        'NPC_PROXIMITY_SOURCE_RULE:' if original.get('geometry',{}).get('operator')=='OBSERVED_API_OCCUPIED_BOUNDS_PROXIMITY'
                        else 'NPC_BODY_SOURCE_RULE:'}[mode]}
            for key in ('familyKey', 'geometry', 'contextPredicate', 'requiredObservations'):
                if key in original:
                    row[key] = deepcopy(original[key])
            if section == 'observedNpcContactHints':
                row['source']['names'] = [original['name']]
            if mode == 'ENDPOINT_COOCCURRENCE':
                row['source']['names'] = deepcopy(original['sourceNames'])
            if 'geometry' in original:
                row['geometry'] = geometry(original['geometry'], kind, mode == 'ENDPOINT_COOCCURRENCE')
            if 'observedHitsplatTypes' in original:
                row['admittedHitsplatTypes'] = original['observedHitsplatTypes']
            if 'literalTexts' in original:
                row['signals'] = {'messages': original['literalTexts'], 'channels': original['channels']}
            if section == 'observedGraphicCues':
                row['signals'] = {'lifecycleEvents': ['graphic_spawn']}
            if 'projectileIds' in original:
                row.setdefault('signals', {})['projectileIds'] = original['projectileIds']
            if mode == 'INVENTORY_POSSESSION':
                row['witnessBindings'] = {key: original[key] for key in ('sourceRef', 'pageHash')}
                row['witnessBindings']['ruleKey'] = original['key']
                row['clockPolicy'] = 'same_callback'
            else:
                row['clockPolicy'] = original.get('temporalRelation', 'same_tick')
            result.append(row)
    for original in references['mechanismRules']['rules'] + references['mechanismRules']['actorRules']:
        source = deepcopy(original['selector']);source['kind'] = source['kind'].lower()
        row = {'id': original['key'], 'evidenceMode': 'RETAINED_LIFECYCLE'
            if original.get('cue', {}).get('type') in ('ACTOR_DEATH', 'NPC_DESPAWNED') else 'ORIGINAL_CONTACT',
            'source': source, 'label': original.get('label'), 'declaration': deepcopy(original),
            'requiredObservations': original.get('requiredObservations', []), 'causalAssociationOnly': True,
            'geometry': geometry(original['footprint'], source['kind']), 'clockPolicy': original['interval']['operator'],
            'evidencePrefix': 'RETAINED_LIFECYCLE_WINDOW_RULE:' if original['interval']['operator']=='STRICT_RETAINED_LIFECYCLE_CUE_WINDOW'
                else 'LIFECYCLE_COOCCURRENCE_RULE:' if original.get('cue',{}).get('type') in ('ACTOR_DEATH','NPC_DESPAWNED') else 'MECHANISM_RULE:'}
        if original.get('cue', {}).get('type') in ('ACTOR_DEATH', 'NPC_DESPAWNED'):
            row['signals'] = {'lifecycleEvents': ['actor_death' if original['cue']['type'] == 'ACTOR_DEATH' else 'npc_despawn']}
            row['contextPredicate'] = {'requiredZeroVariables': ['LEAGUE_TYPE'],
                'excludedWorldTypeFragments': ['SEASONAL', 'DEADMAN', 'TOURNAMENT', 'BETA']}
            row['originalHpPositionRequired'] = True
        row['admittedHitsplatTypes'] = original.get('observedHitsplatTypes', [int(key) for key, value in
            references['hitsplatRules']['rules'].items() if value.get('surface') in ('HP', 'BLOCK') and not value.get('damageType')])
        for key in ('familyKey', 'perHitMaximum'):
            if key in original:
                row[key] = deepcopy(original[key])
        result.append(row)
    return sorted((evidence_admission(row) for row in result), key=lambda row: row['id'])

def evidence_admission(row):
    def c(fact,op,value=None,at='cue',other=None,other_at='cue'):
        rule={'at':at,'fact':fact,'op':op}
        if other is not None:rule['valueFrom']={'at':other_at,'fact':other}
        elif value is not None:rule['value']=value
        return rule
    def same(fact,at='cue',other=None,other_at='hit'):
        return {'all':[c(fact,'type_is','number',at=at),c(fact if other is None else other,'type_is','number',at=other_at),c(fact,'equals',at=at,other=fact if other is None else other,other_at=other_at)]}
    terms=[]
    for flag in row.get('requiredObservations',[]):
        if flag=='SOURCE_ACTOR_IDENTITY_KNOWN':
            terms.extend([c('actorRef','type_is','string',at='source'),c('id','type_is','integer',at='source')])
        elif flag=='SOURCE_DEFINITION_BOUND':
            terms.append(c('originalActorState.definitionEvidence','one_of',['NPC_API_ID','TRANSFORMED_COMPOSITION'])
                if row['source']['kind']=='npc' else c('id','type_is','integer',at='source'))
        elif flag=='QUALIFIED_NPC_VARIANT':
            assert row['source'].get('names') and row['source'].get('combatLevels'),row['id']
        elif flag=='NORMAL_WORLD_CONTEXT':terms.append(c('originalActorState.normalWorldContextObserved','equals',True))
        elif flag=='NO_MODEL_OVERRIDES_OBSERVED':terms.append(c('originalActorState.modelOverridesPresent','equals',False))
        elif flag in ('SOURCE_POSITION_KNOWN','IMPACT_POSITION_KNOWN'):
            terms.extend(c('position.'+axis,'type_is','number',at='cue' if flag=='SOURCE_POSITION_KNOWN' else 'hit') for axis in ['x','y'])
        elif flag=='SAME_NATIVE_SCOPE':terms.extend(c('scope.'+key,'equals',at='source',other='scope.'+key,other_at='hit') for key in ['sceneKey','worldViewId','plane'])
        elif flag=='SAME_RECORDED_TICK':terms.append(same('tick'))
        elif flag=='KNOWN_LOCAL_HP_CALLBACK':terms.extend([c('originalHpCallback','type_is','nonempty_object',at='hit'),
            c('hitsplatType','type_is','integer',at='hit'),c('sequence','gt',0,at='hit')])
        elif flag=='CUE_BEFORE_HIT':terms.extend([c('sequence','gt',0),c('sequence','lt',other='sequence',other_at='hit'),
            c('cycle','lte',other='cycle',other_at='hit')])
        elif flag=='RETAINED_CUE_WINDOW_COMPLETE':row['retainedWindowRequired']=True
        elif flag=='ACTIVE_INTERVAL_OBSERVED':terms.append(c('source_active','equals',True,at='impact'))
        else:raise AssertionError(('Untranslated admission flag',row['id'],flag))
    mode=row['evidenceMode'];raw=row['declaration'];clock=row.get('clockPolicy')
    if mode=='INVENTORY_POSSESSION':
        terms.extend([c('witness.kind','equals','ITEM_POSSESSION_CANDIDATE'),c('amount','gt',0,at='hit'),
            same('witness.itemId',other='id',other_at='source'),c('witness.name','equals',row['label']),
            c('witness.slot','type_is','integer'),c('witness.slot','gte',0),c('witness.slot','lt',28),
            c('witness.quantity','gt',0),c('witness.containerKnown','equals',True),c('witness.localRecipientKnown','equals',True),
            same('witness.observedTick',other='tick'),same('witness.observedCycle',other='cycle'),same('witness.observationSequence',other='sequence')])
    elif mode!='SYSTEM_RESPONSE':
        if raw.get('observationKind')=='RECORDED_NPC_SNAPSHOT':
            terms.extend([c('originalActorState.normalWorldContextObserved','equals',True),c('originalActorState.modelOverridesPresent','equals',False),
                c('originalActorState.definitionEvidence','one_of',['NPC_API_ID','TRANSFORMED_COMPOSITION']),
                c('originalActorState.worldAreaKnown','equals',True),c('originalActorState.worldAreaWorldViewKnown','equals',True),
                c('originalActorState.worldAreaEvidence','equals','ACTOR_GET_WORLD_AREA'),
                same('originalActorState.worldAreaWorldViewId',other='scope.worldViewId',other_at='source'),
                same('originalActorState.worldArea.plane',other='scope.plane',other_at='source')])
        if clock=='same_callback':terms.extend(same(key) for key in ['tick','cycle','sequence'])
        elif clock in ('SAME_RECORDED_TICK','SAME_RECORDED_TICK_CO_OCCURRENCE','SAME_RECORDED_TICK_LIFECYCLE_COOCCURRENCE'):terms.append(same('tick'))
        elif clock=='OBSERVED_ACTIVE':terms.append(c('source_active','equals',True,at='impact'))
    row['admissionWhen']={'all':terms}
    row['geometryRequired']=mode not in ('INVENTORY_POSSESSION','SYSTEM_RESPONSE')
    return row


def contact_policies(capture):
    result = []
    ordinary = {12, 13, 16, 17, 43}
    for identifier, original in capture['objectHazardRules'].items():
        policy = original.get('contactAdmission')
        if original.get('surfaceOperator') not in ('CONDITIONAL_HP_TILE', 'CONDITIONAL_HP_OBJECT_FOOTPRINT'):
            assert policy is None
            continue
        if policy is None:
            result.append({'id': original['key'], 'source': {'kind': 'object', 'ids': [int(identifier)]},
                'surfaceOperator': original['surfaceOperator']})
            continue
        assert original['surfaceOperator'] in ('CONDITIONAL_HP_TILE', 'CONDITIONAL_HP_OBJECT_FOOTPRINT')
        assert set(policy) == ({'outcomeDomain', 'observedHitsplatTypes', 'perHitMaximum', 'maximumWitness'}
            if 'perHitMaximum' in policy else {'outcomeDomain', 'observedHitsplatTypes'})
        assert policy['outcomeDomain'] == 'ORDINARY_HP_OR_BLOCK_CALLBACK_ONLY'
        assert len(policy['observedHitsplatTypes']) == 5 and set(policy['observedHitsplatTypes']) == ordinary
        if 'perHitMaximum' in policy:
            maximum, witness = policy['perHitMaximum'], policy['maximumWitness']
            assert type(maximum) is int and 0 <= maximum <= 1000000
            assert set(witness) == {'page', 'pageHash', 'start', 'end', 'text', 'quantity'}
            assert witness['page'].strip() and len(witness['pageHash']) == 64 and all(c in 'abcdef0123456789' for c in witness['pageHash'])
            assert witness['start'] >= 0 and witness['end'] > witness['start'] and witness['text'] == str(maximum)
            assert witness['quantity'] == 'OBJECT_CONTACT_HP_PER_CALLBACK_MAXIMUM'
        result.append({'id': original['key'], 'source': {'kind': 'object', 'ids': [int(identifier)]},
            'surfaceOperator': original['surfaceOperator'], **deepcopy(policy),
            'contextPredicate': {'requiredZeroVariables': ['LEAGUE_TYPE'],
                'excludedWorldTypeFragments': ['SEASONAL', 'DEADMAN', 'TOURNAMENT', 'BETA']}})
    return result

def original_fact_bindings():
    return [{'id': 'original-fact:10',
      'role': 'fact',
      'when': {'at': 'cue',
               'fact': 'witness.originalActorState',
               'op': 'type_is',
               'value': 'nonempty_object'},
      'effects': {'assign': [{'target': 'originalActorState',
                              'valueFrom': {'at': 'cue', 'fact': 'witness.originalActorState'}}]}},
     {'id': 'original-fact:11',
      'role': 'fact',
      'when': {'at': 'cue', 'fact': 'witness.actorState', 'op': 'type_is', 'value': 'nonempty_object'},
      'effects': {'assign': [{'target': 'originalActorState',
                              'valueFrom': {'at': 'cue', 'fact': 'witness.actorState'}}]}},
     {'id': 'original-fact:0',
      'role': 'fact',
      'when': {'all': [{'at': 'cue',
                        'fact': 'originalActorState.normalWorldContextObserved',
                        'op': 'equals',
                        'value': True},
                       {'at': 'cue',
                        'fact': 'originalActorState.observationIssues',
                        'op': 'no_issue',
                        'value': ['normalWorldContextObserved', 'worldTypes', 'leagueType']}]},
      'effects': {'assign': [{'target': 'facts.source_event.world_mode', 'value': 'NORMAL'}]}},
     {'id': 'original-fact:1',
      'role': 'fact',
      'when': {'all': [{'at': 'cue',
                        'fact': 'originalActorState.modelOverridesPresent',
                        'op': 'type_is',
                        'value': 'boolean'},
                       {'at': 'cue',
                        'fact': 'originalActorState.observationIssues',
                        'op': 'no_issue',
                        'value': ['modelOverrides']}]},
      'effects': {'assign': [{'target': 'facts.source_event.source_model_overrides',
                              'valueFrom': {'at': 'cue',
                                            'fact': 'originalActorState.modelOverridesPresent'}}]}},
     {'id': 'original-fact:2',
      'role': 'fact',
      'when': {'at': 'cue',
               'fact': 'originalActorState.worldPositionKnown',
               'op': 'equals',
               'value': True},
      'effects': {'assign': [{'target': 'position.x',
                              'valueFrom': {'at': 'cue', 'fact': 'originalActorState.worldX'}}]}},
     {'id': 'original-fact:3',
      'role': 'fact',
      'when': {'at': 'cue',
               'fact': 'originalActorState.worldPositionKnown',
               'op': 'equals',
               'value': True},
      'effects': {'assign': [{'target': 'position.y',
                              'valueFrom': {'at': 'cue', 'fact': 'originalActorState.worldY'}}]}},
     {'id': 'original-fact:4',
      'role': 'fact',
      'when': {'at': 'cue', 'fact': 'originalActorState.worldAreaKnown', 'op': 'equals', 'value': True},
      'effects': {'assign': [{'target': 'bounds',
                              'valueFrom': {'at': 'cue', 'fact': 'originalActorState.worldArea'}}]}},
     {'id': 'original-fact:5',
      'role': 'fact',
      'when': {'at': 'cue',
               'fact': 'originalActorState.interaction.actorRef',
               'op': 'type_is',
               'value': 'string'},
      'effects': {'assign': [{'target': 'targetRef',
                              'valueFrom': {'at': 'cue',
                                            'fact': 'originalActorState.interaction.actorRef'}}]}},
     {'id': 'original-fact:6',
      'role': 'fact',
      'when': {'at': 'cue',
               'fact': 'originalSourceObservation.sourceNormalWorldContextObserved',
               'op': 'equals',
               'value': True},
      'effects': {'assign': [{'target': 'facts.source_event.world_mode', 'value': 'NORMAL'}]}},
     {'id': 'original-fact:7',
      'role': 'fact',
      'when': {'at': 'cue',
               'fact': 'originalSourceObservation.sourceModelOverridesPresent',
               'op': 'type_is',
               'value': 'boolean'},
      'effects': {'assign': [{'target': 'facts.source_event.source_model_overrides',
                              'valueFrom': {'at': 'cue',
                                            'fact': 'originalSourceObservation.sourceModelOverridesPresent'}}]}},
     {'id': 'original-fact:8',
      'role': 'fact',
      'when': {'at': 'cue', 'fact': 'witness.destinationKnown', 'op': 'equals', 'value': True},
      'effects': {'assign': [{'target': 'endpoint.x',
                              'valueFrom': {'at': 'cue', 'fact': 'witness.endWorldX'}}]}},
     {'id': 'original-fact:9',
      'role': 'fact',
      'when': {'at': 'cue', 'fact': 'witness.destinationKnown', 'op': 'equals', 'value': True},
      'effects': {'assign': [{'target': 'endpoint.y',
                              'valueFrom': {'at': 'cue', 'fact': 'witness.endWorldY'}}]}}]

def build(repo, input_dir=None):
    references, reference_hash = read_baseline(repo, 'combat_catalog.json', input_dir)
    capture, capture_hash = read_baseline(repo, 'damage_map.json', input_dir)
    resolver, resolver_hash = read_baseline(repo, 'resolver-v1.json', input_dir)
    resolver['qualifiedLimits'] = limits(references)
    resolver['wholeSourceHitsplatTypes'] = sorted({value for row in references['attributionConstraints']['rules']
        if row.get('boundBasis') == 'QUALIFIED_WHOLE_ACTOR_ENVELOPE' for value in row['allowedHitsplatTypes']})
    resolver['evidenceRules'] = evidence_rules(references)
    resolver['evidenceRules'].append({'id':'interaction-response:pickpocket','evidenceMode':'INTERACTION_RESPONSE',
        'source':{'kind':'npc'}, 'request':{'operation':'Pickpocket','maximumAgeTicks':2,'maximumDistanceTiles':2},
        'response':{'messageRegex':r"You fail to pick (.+)'s pocket\.",'channels':['GAMEMESSAGE','SPAM']},
        'admission':{'hitsplatTypeIds':[int(key) for key,value in references['hitsplatRules']['rules'].items()
            if value['name'] in ('DAMAGE_ME','DAMAGE_OTHER','DAMAGE_MAX_ME')], 'positiveCallbacksPerTick':1},
        'labelSuffix':' (pickpocket)','fallbackLabel':capture['damageRules']['pickpocket']['name'],
        'sourceAssociation':'PICKPOCKET_FAILURE_CORRELATION','referenceAnnotations':{
            'damageType':capture['damageRules']['pickpocket']['damageType'],
            'attackStyleName':capture['damageRules']['pickpocket']['name'],
            'mapRef':capture['damageRules']['pickpocket']['mapRef']},
        'declaration':deepcopy(capture['damageRules']['pickpocket']),
        'policyOrigin':{'commit':BASELINE,'path':'src/main/java/com/tickreplaylogger/TickReplayLoggerPlugin.java',
            'role':'existing_server_failure_and_recent_menu_correlation_not_execution_proof'}})
    resolver['contactPolicies'] = contact_policies(capture)
    resolver['inputLimits'] = deepcopy(references['selfInputConstraints']['rules'])
    resolver['outcomeBindings'] = deepcopy(references['damageCompatibility'])
    lowered = lower_resolution_rules(resolver)
    resolver.update(resolution_metadata(resolver))
    for table, role in TABLES:
        del resolver[table]
    del resolver['outcomeBindings']
    resolver['ruleIrVersion'] = 1
    resolver['resolutionRules'] = lowered + original_fact_bindings() + lower_reference_annotations(references, capture)
    references['inventorySourceCapture'] = lower_inventory_capture(references['observedInventorySourceHints'], references['hitsplatRules'])
    result = {'schemaVersion': 1, 'purpose': 'retrospective_combat_mechanics',
        'sourceSnapshots': {'baseline': BASELINE, 'combatCatalogSha256': reference_hash,
            'captureBindingsSha256': capture_hash, 'resolverSha256': resolver_hash},
        'capture': {key: value for key, value in capture.items() if key in RUNTIME_CAPTURE_KEYS},
        'references': {key: value for key, value in references.items() if key in RUNTIME_REFERENCE_KEYS},
        'resolver': resolver}
    signature = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf8')
    result['catalogId'] = 'mechanics:sha256:' + hashlib.sha256(signature).hexdigest()
    return result

def serialize(document):
    # Retain the existing readable resolver formatting and single-line reference-table style.
    # Runtime facts remain inspectable; historical authoring inputs stay under tests.
    header = {key: value for key, value in document.items() if key not in ('capture', 'references', 'resolver')}
    text = json.dumps(header, ensure_ascii=False, indent=2)[:-2]
    return (text + ',\n  "capture": ' + json.dumps(document['capture'], ensure_ascii=False, separators=(',', ':'))
        + ',\n  "references": ' + json.dumps(document['references'], ensure_ascii=False, separators=(',', ':'))
        + ',\n  "resolver": ' + json.dumps(document['resolver'], ensure_ascii=False, indent=2) + '\n}\n')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--input-dir', type=Path, help='Directory containing the three frozen authoring JSON files')
    args = parser.parse_args()
    document = build(args.repo, args.input_dir)
    output = args.repo / BASE / 'runtime-mechanics.json'
    content = serialize(document)
    # Always prove packaging is lossless and syntactically valid before writing.
    assert json.loads(content) == document
    if args.check:
        assert output.read_text(encoding='utf8') == content, 'Runtime mechanics resource differs'
    else:
        output.write_text(content, encoding='utf8', newline='\n')
    print(json.dumps({'catalogId': document['catalogId'], 'resolutionRules': len(document['resolver']['resolutionRules']),
        'referenceSections': len(document['references']), 'resourceBytes': len(content.encode('utf8'))}))
