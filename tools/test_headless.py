"""Aceptación real por stdio: proceso Equinox, SDK MCP y archivos nativos."""
import argparse
import json
import pathlib
import queue
import subprocess
import threading
import time
import uuid
import psutil
import os


class Client:
    def __init__(self, root, workspace, evidence):
        self.evidence = evidence
        self.events = []
        self.responses = queue.Queue()
        self.last_activity = time.monotonic()
        self.last_metrics = None
        self.log = open(evidence / (str(uuid.uuid4()) + '.stderr.log'), 'w', encoding='utf-8')
        self.transcript = open(evidence / (str(uuid.uuid4()) + '.jsonl'), 'w', encoding='utf-8')
        command = ['pwsh', '-NoProfile', '-File', str(root / 'tools/start-headless.ps1'), '-Workspace', str(workspace)]
        if os.environ.get('AXIOMA_TEST_RUNTIME'):
            command.extend(['-RuntimeHome', os.environ['AXIOMA_TEST_RUNTIME']])
        self.process = subprocess.Popen(command,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding='utf-8', bufsize=1)
        threading.Thread(target=self._stdout, daemon=True).start()
        threading.Thread(target=self._stderr, daemon=True).start()
        self.counter = 0
        self.request('initialize', {'protocolVersion': '2025-06-18', 'capabilities': {},
                                    'clientInfo': {'name': 'axioma-aceptacion', 'version': '1.0'}})
        self.send({'jsonrpc': '2.0', 'method': 'notifications/initialized'})

    def _stdout(self):
        for line in self.process.stdout:
            self.last_activity = time.monotonic()
            try:
                self.responses.put(json.loads(line))
            except ValueError:
                self.responses.put({'invalidStdout': line})

    def _stderr(self):
        for line in self.process.stderr:
            self.last_activity = time.monotonic()
            self.log.write(line)
            self.log.flush()

    def send(self, data):
        self.transcript.write(json.dumps({'direction': 'request', 'data': data}, ensure_ascii=False) + '\n')
        self.transcript.flush()
        self.process.stdin.write(json.dumps(data, ensure_ascii=False) + '\n')
        self.process.stdin.flush()

    def request(self, method, params):
        self.counter += 1
        ident = self.counter
        self.send({'jsonrpc': '2.0', 'id': ident, 'method': method, 'params': params})
        while True:
            try:
                response = self.responses.get(timeout=2)
            except queue.Empty:
                if self.process.poll() is not None:
                    raise AssertionError(f'Proceso terminó {self.process.returncode}; consultar stderr')
                # Renovar la ventana si CPU/E/S del árbol administrado siguen avanzando.
                self.observe_activity()
                if time.monotonic() - self.last_activity > 60:
                    raise AssertionError('Sin actividad verificable durante 60 segundos')
                continue
            assert 'invalidStdout' not in response, response
            self.events.append(response)
            self.transcript.write(json.dumps({'direction': 'response', 'data': response}, ensure_ascii=False) + '\n')
            self.transcript.flush()
            if response.get('id') == ident:
                assert 'error' not in response, response
                return response['result']

    def observe_activity(self):
        metrics = []
        try:
            parent = psutil.Process(self.process.pid)
            for process in [parent, *parent.children(recursive=True)]:
                try:
                    cpu, io = process.cpu_times(), process.io_counters()
                    metrics.append((process.pid, cpu.user + cpu.system, io.read_bytes, io.write_bytes))
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
        except psutil.NoSuchProcess:
            return
        current = tuple(sorted(metrics))
        if current and current != self.last_metrics:
            self.last_activity = time.monotonic()
            self.last_metrics = current

    def call(self, domain, action, error=None, **args):
        result = self.request('tools/call', {'name': 'axioma_' + domain, 'arguments': {'action': action, **args}})
        value = result.get('structuredContent') or json.loads(result['content'][0]['text'])
        if error:
            assert result.get('isError') and value['error']['code'] == error, value
            return value
        assert not result.get('isError'), value
        return value['data']

    def close(self):
        self.process.stdin.close()
        # Salida es una etapa atómica de limpieza, no una operación de negocio.
        code = self.process.wait(timeout=20)
        assert code == 0, code
        (self.evidence / (str(uuid.uuid4()) + '.json')).write_text(
            json.dumps(self.events, ensure_ascii=False, indent=2), encoding='utf-8')
        self.log.close()
        self.transcript.close()


def test(root):
    evidence = root / '.axioma-local' / ('aceptacion-' + time.strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir(parents=True)
    workspace = evidence / 'modelos'
    client = Client(root, workspace, evidence)
    try:
        tools = client.request('tools/list', {})
        assert len(tools['tools']) >= 7
        runtime = client.call('runtime', 'info')
        assert runtime['mode'] == 'headless' and not runtime['workbenchRunning'], runtime
        first = client.call('model', 'create', name='Modelo A', path='a.archimate')
        second = client.call('model', 'create', name='Modelo B', path='b.archimate')
        a, b = first['modelId'], second['modelId']
        made = client.call('batch', 'apply', modelId=a, operations=[
            {'domain': 'element', 'action': 'create', 'type': 'BusinessActor', 'name': 'Operador', 'as': 'actor'},
            {'domain': 'element', 'action': 'create', 'type': 'BusinessProcess', 'name': 'Gestionar', 'as': 'proceso'},
            {'domain': 'relationship', 'action': 'create', 'type': 'Assignment', 'sourceId': '$actor.id', 'targetId': '$proceso.id', 'as': 'rel'},
            {'domain': 'view', 'action': 'create', 'name': 'Vista integrada', 'as': 'vista'},
            {'domain': 'view', 'action': 'addElement', 'viewId': '$vista.id', 'elementId': '$actor.id', 'x': 20, 'y': 20, 'as': 'va'},
            {'domain': 'view', 'action': 'addElement', 'viewId': '$vista.id', 'elementId': '$proceso.id', 'x': 300, 'y': 20, 'as': 'vp'},
            {'domain': 'view', 'action': 'addConnection', 'viewId': '$vista.id', 'relationshipId': '$rel.id', 'sourceVisualId': '$va.id', 'targetVisualId': '$vp.id'},
        ])
        assert made['persisted']
        effective_test = client.call('batch', 'apply', modelId=b, operations=[
            {'domain': 'element', 'action': 'create', 'type': 'BusinessActor', 'name': 'Inicial', 'as': 'actor'},
            {'domain': 'element', 'action': 'update', 'id': '$actor.id', 'name': 'Nombre final'},
        ])
        assert effective_test['result']['references']['actor']['name'] == 'Nombre final'
        assert effective_test['result']['operationResultsAreIntermediate'] is True
        client.call('element', 'delete', modelId=b, id=effective_test['result']['references']['actor']['id'])
        refs = made['result']['references']
        view_id = refs['vista']['id']
        assert client.call('view', 'layout', modelId=a, viewId=view_id, direction='RIGHT')['result']['elementsRepositioned'] == 2
        for fmt in ['svg', 'png']:
            export = client.call('view', 'export', modelId=a, viewId=view_id, path='salidas/vista.' + fmt, format=fmt)
            assert export['bytes'] > 100
        # El raster debe conservar alpha real, no quitar por color las figuras blancas.
        import struct
        png = (workspace / 'salidas/vista.png').read_bytes()
        assert png[:8] == b'\x89PNG\r\n\x1a\n' and png[25] == 6
        assert client.call('runtime', 'info')['workbenchRunning'] is False
        assert client.call('element', 'list', modelId=b) == []
        actor = made['result']['references']['actor']['id']
        client.call('element', 'get', modelId=b, id=actor, error='OBJECT_NOT_FOUND')
        before = (workspace / 'a.archimate').read_bytes()
        client.call('model', 'update', modelId=a, nmae='Error de escritura', error='UNKNOWN_ARGUMENT')
        client.call('element', 'create', modelId=a, type='ImaginaryElement', name='Inválido', error='INVALID_ARCHIMATE_TYPE')
        client.call('batch', 'apply', modelId=a, operations=[
            {'domain': 'element', 'action': 'create', 'type': 'BusinessActor', 'name': 'No persistir'},
            {'domain': 'element', 'action': 'create', 'type': 'NoExiste', 'name': 'Fallo'},
        ], error='INVALID_ARCHIMATE_TYPE')
        preview = client.call('element', 'create', modelId=a, type='BusinessRole', name='Ensayo', dryRun=True)
        assert not preview['persisted']
        assert (workspace / 'a.archimate').read_bytes() == before
        client.call('element', 'update', modelId=a, id=actor, name='No guardar', expectedRevision=0, error='REVISION_CONFLICT')
        client.call('element', 'update', modelId=a, id=actor, name='No guardar', expectedFingerprint='0' * 64, error='FINGERPRINT_CONFLICT')
        client.call('model', 'create', name='Escape', path='../escape.archimate', error='PATH_OUTSIDE_WORKSPACE')
        client.call('model', 'create', name='Reservado', path='.axioma/no-tocar.archimate', error='RESERVED_PATH')
        assert client.call('model', 'validate', modelId=a)['errors'] == []
        client.call('element', 'update', modelId=b, id=actor, name='No', error='OBJECT_NOT_FOUND')
        # Conflicto real: otro escritor modifica el archivo entre operaciones.
        with (workspace / 'a.archimate').open('ab') as out:
            out.write(b'\n')
        external = (workspace / 'a.archimate').read_bytes()
        client.call('element', 'update', modelId=a, id=actor, name='Conflicto', error='EXTERNAL_FILE_CONFLICT')
        assert (workspace / 'a.archimate').read_bytes() == external
        client.call('model', 'reload', modelId=a)
        client.call('element', 'update', modelId=a, id=actor, name='Operación actualizada')
        client.close()
        client = Client(root, workspace, evidence)
        reopened = client.call('model', 'open', path='a.archimate')
        assert reopened['modelId'] == a
        assert client.call('element', 'get', modelId=a, id=actor)['name'] == 'Operación actualizada'
        assert len(client.call('relationship', 'list', modelId=a)) == 1
        assert len(client.call('view', 'list', modelId=a)) == 1
        client.call('model', 'open', path='b.archimate')
        assert client.call('element', 'list', modelId=b) == []
        client.close()
        (evidence / 'resultado.json').write_text(json.dumps({'status': 'PASS', 'runtime': runtime, 'modelIds': [a, b]}, indent=2), encoding='utf-8')
        print(json.dumps({'status': 'PASS', 'evidence': str(evidence)}, ensure_ascii=False))
    except Exception:
        print('Evidencia del fallo:', evidence)
        # Sólo procesos propios; conservar evidencia antes de propagar el fallo real.
        if client.process.poll() is None:
            client.process.stdin.close()
            try:
                client.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                subprocess.run(['taskkill', '/PID', str(client.process.pid), '/T', '/F'], capture_output=True)
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    test(parser.parse_args().root)
