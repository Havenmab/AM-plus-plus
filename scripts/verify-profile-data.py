#!/usr/bin/env python3
"""Validate packaged profile data and frozen compatibility snapshots without a device."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / 'host-applemusic/src/main/resources/host-profiles'

def main():
    index = json.loads((DIRECTORY/'index.json').read_text(encoding='utf-8'))
    assert index['schemaVersion'] == 1
    tuples = set()
    target_count = 0
    for filename in index['profiles']:
        path = (DIRECTORY/filename).resolve()
        assert path.parent == DIRECTORY.resolve()
        profile = json.loads(path.read_text(encoding='utf-8'))
        key = (profile['packageName'],profile['versionName'],profile['versionCode'])
        assert key not in tuples, f'duplicate tuple: {key}'
        tuples.add(key)
        assert filename == f"{key[1]}-{key[2]}.json"
        assert profile['schemaVersion'] == 1
        assert profile['family'] in ('legacy-activity', 'fragment-content')
        assert profile['verification']['version_code'] == str(key[2])
        if 'lyricWrapSweep' in profile:
            wrap = profile['lyricWrapSweep']
            assert wrap.get('contractVersion') == 1, f'missing wrap sweep contract: {filename}'
            required_wrap = {
                'methods': {'build', 'update', 'rushUpdate', 'fadeUpdate', 'staticMask', 'rebind', 'bind', 'recycle', 'document',
                            'clearMasks', 'textView', 'maskUpdate', 'animationTag', 'mediaBrowser',
                            'mediaPosition', 'seek', 'pause', 'pauseRequested', 'resume'},
                'fields': {'topMasks', 'bottomMasks', 'maskFirst', 'maskLast', 'wordBegin', 'wordDuration',
                           'bindingRoot', 'holderBinding', 'adapterCallback', 'callbackFragment', 'featherPixels',
                           'listenerMask', 'listenerColors', 'listenerFeather', 'listenerRtl', 'wordText', 'wordAnimator',
                           'words', 'backgroundWords', 'pronunciationWords', 'pronunciationBackgroundWords',
                           'foregroundInactiveColors', 'backgroundInactiveColors', 'fadeMask'},
            }
            for group, roles in required_wrap.items():
                assert roles <= wrap.get(group, {}).keys(), f'missing wrap sweep {group}: {filename}'
            assert profile['productionEnabled'], f'wrap sweep must use an enabled exact profile: {filename}'
        if 'lyricLineMotion' in profile:
            # 「换行弹簧位移」 is the only line-change feature left: the rhythm scaling, its probe
            # and the animator's move-duration role were removed, so the section is a container
            # holding the spring takeover and the interlude exit it waits for.
            motion = profile['lyricLineMotion']
            spring = motion.get('spring') or {}
            assert spring.get('contractVersion') == 1, f'missing line motion spring contract: {filename}'
            # rowSettle is the host's own per-row "this row's move is over". It is optional *at
            # runtime* (a build without it keeps the animation and loses one callback), but a
            # profile that installs the spring must pin it: without it the host's own line update
            # stops arriving, which is a regression rather than a missing extra.
            required_spring = {
                'classes': {'animator', 'holder'},
                'methods': {'animateMove', 'isRunning', 'endAnimation', 'endAnimations',
                            'dispatchMoveStarting', 'dispatchAnimationFinished', 'rowSettle'},
                'fields': {'holderItemView', 'animatorFragment'},
            }
            for group, roles in required_spring.items():
                assert roles <= spring.get(group, {}).keys(), f'missing line motion spring {group}: {filename}'
            assert profile['productionEnabled'], f'line motion must use an enabled exact profile: {filename}'
            # The interlude's three-dot exit animation: read-only alignment, so the runtime degrades
            # one missing role at a time (no end callback means "wait the book duration", no start
            # role means "never wait at all"). The profile is where every role it may use is named.
            interlude = motion.get('interlude') or {}
            assert interlude.get('contractVersion') == 1, f'missing line motion interlude contract: {filename}'
            required_interlude = {
                'classes': {'instrumentalBinding'},
                'methods': {'wordExit', 'lineExit', 'wordExitEnd', 'lineExitEnd'},
                'fields': {'holderBinding'},
            }
            for group, roles in required_interlude.items():
                assert roles <= interlude.get(group, {}).keys(), f'missing line motion interlude {group}: {filename}'
            # The host's own line-change callback (K#b in 1626): the upstream count the spring's
            # log is read against. Optional per role at runtime -- no role means no line-change
            # line, never a failed spring -- but a profile that declares the section must pin it.
            trace = motion.get('trace') or {}
            if trace:
                assert trace.get('contractVersion') == 1, f'missing line motion trace contract: {filename}'
                required_trace = {'methods': {'lineChange'}}
                for group, roles in required_trace.items():
                    assert roles <= trace.get(group, {}).keys(), f'missing line motion trace {group}: {filename}'
        artist_targets = profile['hookTargets'].get('ARTIST_SURFACE_CLASSES', [])
        artist_roles = [target['runtimeMemberNames'].get('ARTIST_RUNTIME_ROLE') for target in artist_targets]
        compose_roles = {'compose_profile_view_model', 'compose_profile_success_state'}
        if 'artistComposeProfile' in profile or compose_roles.intersection(artist_roles):
            assert all(artist_roles.count(role) == 1 for role in compose_roles), f'incomplete Compose artist roles: {filename}'
            contract = profile.get('artistComposeProfile', {})
            assert contract.get('contractVersion') == 1, f'missing Compose artist contract: {filename}'
            required = {
                'methods': {'setUiState', 'headerData', 'shelves', 'entityType', 'entityTitle', 'entityViews', 'viewEntities'},
                'fields': {'id', 'title', 'header', 'shelves', 'entity', 'revision'},
                'constructors': {'success'},
            }
            for group, roles in required.items():
                assert roles <= contract.get(group, {}).keys(), f'missing Compose artist {group}: {filename}'
            assert contract.get('artistType') and contract.get('topSongsView'), f'missing Compose artist semantic keys: {filename}'
        if not profile['productionEnabled']:
            assert not any(profile['capabilities'].values())
        if profile['productionEnabled'] and profile['family'] == 'fragment-content':
            for point in ('LOCAL_MEDIA_PLAYER_METADATA_UPDATED', 'LOCAL_MEDIA_PLAYER_INDEX_CHANGED',
                          'LOCAL_MEDIA_PLAYER_CONTROLLER_STATE'):
                targets = profile['hookTargets'].get(point, [])
                assert targets, f'missing required metadata bootstrap: {filename}/{point}'
                for target in targets:
                    assert target['methodName'] and target['parameterTypeNames'] is not None
                    assert target['returnTypeName'] == 'void' and target['isStatic'] is False
                    assert not target['allowFirstMatch']
            state = profile['hookTargets']['LOCAL_MEDIA_PLAYER_CONTROLLER_STATE'][0]['runtimeMemberNames']
            required = ('PLAYER_CURRENT_ITEM', 'QUEUE_ITEM_ITEM', 'QUEUE_ITEM_ID', 'MEDIA_ITEM_GENRE_NAME',
                        'MEDIA_ITEM_DURATION', 'MEDIA_ITEM_TITLE', 'MEDIA_ITEM_SUBSCRIPTION_STORE_ID',
                        'MEDIA_ITEM_PERSISTENT_ID', 'MEDIA_ITEM_ARTIST_NAME')
            assert all(state.get(f'PLAYBACK_{member}_METHOD') for member in required), f'missing playback getters: {filename}'
        for point,targets in profile['hookTargets'].items():
            for target in targets:
                target_count += 1
                assert target['className'] and target['contractId'] == point
                assert target['resolutionPolicy'] in ('legacy-reviewed-candidates','exact-required','reviewed-fallback')
                if target['parameterTypeNames'] is not None and target['parameterCount'] is not None:
                    assert len(target['parameterTypeNames']) == target['parameterCount']
        baseline_path = ROOT/'host-applemusic/src/test/resources/baseline'/filename
        assert profile['chrome']['resources'] and profile['settings']['fragmentClass']
        assert len(profile['legacyFirstMatchExceptions']) == len(set(profile['legacyFirstMatchExceptions']))
        if not baseline_path.is_file():
            assert profile.get('ambiguityPolicy', 'reject-ambiguous') == 'reject-ambiguous'
            assert not profile.get('legacyFirstMatchExceptions'), 'new profiles cannot inherit first-match exceptions'
            for target_list in profile['hookTargets'].values():
                assert all(not target['allowFirstMatch'] for target in target_list), 'new profiles must reject ambiguous members'
            for symbol, contract in profile['indexed'].get('methodContracts', {}).items():
                assert symbol and contract['owner'] and contract['name'] and contract['returns']
                assert isinstance(contract['static'], bool) and isinstance(contract['parameters'], list)
                assert all(isinstance(value, str) and value for value in contract['parameters'])
            for symbol, contract in profile['indexed'].get('fieldContracts', {}).items():
                assert symbol and all(contract[key] for key in ('owner', 'name', 'type'))
        if baseline_path.is_file():
            baseline = json.loads(baseline_path.read_text(encoding='utf-8'))
            assert profile['indexed'] == baseline['indexed'], f'indexed baseline drift: {filename}'
            for point,targets in baseline['hookTargets'].items():
                current = profile['hookTargets'][point]
                assert len(current) == len(targets), f'candidate count drift: {filename}/{point}'
                for old,new in zip(targets,current):
                    assert all(new[key] == value for key,value in old.items()), f'target/order drift: {filename}/{point}'
    print(f'PASS: {len(tuples)} exact profiles, {target_count} targets; frozen indexed/HLE data and candidate order preserved')

if __name__ == '__main__':
    main()
