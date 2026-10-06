"""Check saved A24 evidence consistency, including repeated Canvas draws."""
from pathlib import Path
import hashlib, json, math, statistics

root = Path(__file__).resolve().parents[2]
e = root/'docs/evidence/a24'
rows = json.loads((e/'measurements.json').read_text())
summary = json.loads((e/'summary.json').read_text())
assert len(rows) == 10 and summary['status'] == 'PASS'

def first(events, flag, epoch):
    seen = set()
    result = []
    for event in events:
        if not event.get(flag) or event.get('epoch') != epoch:
            continue
        if event['deliveryId'] in seen:
            continue
        seen.add(event['deliveryId'])
        result.append(event)
    return result

# A retained miniature repainted later must not become a second delivery.
fixture = [{'_draw': True, 'epoch': 2, 'deliveryId': 1, 't': 10},
           {'_draw': True, 'epoch': 2, 'deliveryId': 2, 't': 20},
           {'_draw': True, 'epoch': 2, 'deliveryId': 1, 't': 9000},
           {'_draw': True, 'epoch': 3, 'deliveryId': 3, 't': 30}]
assert [v['t'] for v in first(fixture, '_draw', 2)] == [10, 20]

for row in rows:
    assert row['pass'] and row['closedOnReduction'] > 0
    assert row['highLiveBytes'] > row['reducedLiveBytes']
    assert row['refinement']['level'] > row['lowLevel']
    assert row['stale'] == row['mixed'] == row['decodeFailures'] == 0
    trace = json.loads((e/f"{row['image']}-{row['repetition']}-trace.json").read_text())
    for phase in ['initial', 'refinement']:
        m = row[phase]
        draw = first(trace['events'], '_draw', m['epoch'])
        frame = first(trace['events'], '_frame', m['epoch'])
        assert len(draw) == m['sent'] == m['uniqueDrawn'] and m['complete']
        start = draw[0]['t'] - m['firstDrawMs']
        assert math.isclose(draw[-1]['t'] - start, m['completeDrawMs'], abs_tol=1e-6)
        assert math.isclose(frame[-1]['t'] - start, m['completeFrameProxyMs'], abs_tol=1e-6)
        limit = 1000 if phase == 'initial' else 500
        assert m['completeFrameProxyMs'] < limit

for image in summary['images']:
    group = [r for r in rows if r['image'] == image]
    assert len(group) == 5
    for phase in ['initial','refinement']:
        for kind,field in [('Draw','completeDrawMs'),('FrameProxy','completeFrameProxyMs')]:
            v = sorted(r[phase][field] for r in group)
            stat = summary['images'][image][phase+kind+'Ms']
            assert math.isclose(stat['median'], statistics.median(v), abs_tol=1e-6)
            assert math.isclose(stat['p95'], v[math.ceil(len(v)*.95)-1], abs_tol=1e-6)
network=json.loads((e/'network.json').read_text())
assert not network['external'] and not network['errors']
for mode in ['full','late']:
    report=json.loads((e/'acceptance'/f'{mode}.json').read_text())
    assert report['status']=='PASS' and not report['failures']
    assert all(report['checks'].values())
for line in (e/'candidate-files.sha256').read_text().splitlines():
    expected,name=line.split('  ',1)
    assert hashlib.sha256((root/name).read_bytes()).hexdigest()==expected,name
print('A24_EVIDENCE_VALIDATED: 10 runs, raw traces, statistics, acceptance and candidate hashes PASS')
