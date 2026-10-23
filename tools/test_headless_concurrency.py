"""Aceptación de peticiones simultáneas y recuperación tras contención real de archivo."""
import json
import pathlib
import queue
import time
from test_headless import Client


def run():
    root = pathlib.Path(__file__).resolve().parents[1]
    evidence = root / '.axioma-local' / ('concurrencia-' + time.strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir(parents=True)
    workspace = evidence / 'modelos'
    client = Client(root, workspace, evidence)
    try:
        model = client.call('model', 'create', name='Concurrencia', path='concurrencia.archimate')
        mid = model['modelId']
        pending = set()
        for i in range(24):
            client.counter += 1
            pending.add(client.counter)
            client.send({'jsonrpc': '2.0', 'id': client.counter, 'method': 'tools/call', 'params': {
                'name': 'axioma_element', 'arguments': {'action': 'create', 'modelId': mid, 'type': 'BusinessActor',
                                                      'name': 'Operador ' + str(i), 'expectedRevision': 0}}})
        accepted = 0
        rejected = 0
        while pending:
            try:
                response = client.responses.get(timeout=2)
            except queue.Empty:
                client.observe_activity()
                assert client.process.poll() is None, 'El servidor terminó durante las peticiones'
                assert time.monotonic() - client.last_activity < 60, 'Inactividad comprobada'
                continue
            client.events.append(response)
            client.transcript.write(json.dumps({'direction': 'response', 'data': response}, ensure_ascii=False) + '\n')
            client.transcript.flush()
            if response.get('id') not in pending:
                continue
            pending.remove(response['id'])
            result = response['result']
            value = result.get('structuredContent') or json.loads(result['content'][0]['text'])
            if result.get('isError'):
                assert value['error']['code'] == 'REVISION_CONFLICT', value
                rejected += 1
            else:
                assert value['data']['persisted'] is True
                accepted += 1
        assert accepted == 1 and rejected == 23, (accepted, rejected)
        assert len(client.call('element', 'list', modelId=mid)) == 1
        assert client.call('model', 'info', modelId=mid)['revision'] == 1
        # Bloqueo real cooperativo, no una excepción inyectada en un mock del guardado.
        import msvcrt
        lockfile = workspace / 'concurrencia.archimate.axioma.lock'
        with lockfile.open('r+b') as held:
            held.write(b'0')
            held.flush()
            held.seek(0)
            msvcrt.locking(held.fileno(), msvcrt.LK_NBLCK, 1)
            client.call('model', 'update', modelId=mid, name='No debe guardar', error='MODEL_BUSY')
            held.seek(0)
            msvcrt.locking(held.fileno(), msvcrt.LK_UNLCK, 1)
        client.call('model', 'update', modelId=mid, name='Recuperado tras liberar el bloqueo')
        client.call('model', 'close', modelId=mid)
        client.call('model', 'open', path='concurrencia.archimate')
        assert client.call('model', 'info', modelId=mid)['name'] == 'Recuperado tras liberar el bloqueo'
        client.close()
        result = {'status': 'PASS', 'concurrentRequests': 24, 'committed': accepted, 'revisionConflicts': rejected,
                  'fileLockRejected': True, 'recoveryAfterUnlock': True}
        (evidence / 'resultado.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps({**result, 'evidence': str(evidence)}))
    except Exception:
        print('Evidencia del fallo:', evidence)
        if client.process.poll() is None:
            client.process.stdin.close()
            try:
                client.process.wait(timeout=15)
            except Exception:
                import subprocess
                subprocess.run(['taskkill', '/PID', str(client.process.pid), '/T', '/F'], capture_output=True)
        raise


if __name__ == '__main__':
    run()
