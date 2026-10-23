"""MCP real -> archivo nativo -> editor Archi real, con protección de edición humana."""
import json
import pathlib
import re
import subprocess
import time
import os
import psutil
from test_headless import Client


def read_properties(path):
    # Los lectores Windows también pueden encontrar una exclusión breve del indexador o antivirus.
    # Recuperación acotada de la lectura atómica; nunca sustituye los datos ni oculta un error de modelo.
    for attempt in range(6):
        try:
            raw = path.read_text(encoding='ascii')
            break
        except FileNotFoundError:
            return {}
        except PermissionError:
            if attempt == 5:
                raise
            time.sleep(.02 * (attempt + 1))
    result = {}
    for line in raw.splitlines():
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        value = re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m[1], 16)), value)
        result[key] = value.replace('\\:', ':').replace('\\=', '=').replace('\\\\', '\\')
    return result


def run():
    root = pathlib.Path(__file__).resolve().parents[1]
    evidence = root / '.axioma-local' / ('live-' + time.strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir(parents=True)
    workspace = evidence / 'modelos'
    ui = evidence / 'gui'
    ui.mkdir()
    gui = None
    client = Client(root, workspace, evidence)
    logs = []
    metrics = None
    last_activity = time.monotonic()

    def wait(predicate, allow_error=False):
        nonlocal metrics, last_activity
        while True:
            if (ui / 'error.txt').exists():
                raise AssertionError((ui / 'error.txt').read_text(encoding='utf-8'))
            states = sorted(ui.glob('state-*.properties'))
            state = read_properties(states[-1]) if states else {}
            if state.get('menuActivated') == 'false' and (ui / 'ready').exists():
                raise AssertionError('La instancia activada por el menú fue sustituida o detenida inesperadamente: ' + str(state))
            if state.get('liveState') == 'ERROR_PAUSED' and not allow_error:
                raise AssertionError(state)
            if predicate(state):
                logs.append(state)
                (evidence / 'estados.json').write_text(json.dumps(logs, ensure_ascii=False, indent=2), encoding='utf-8')
                return state
            if gui.poll() is not None:
                raise AssertionError(f'Workbench terminó con código {gui.returncode}')
            process = psutil.Process(gui.pid)
            current = []
            for p in [process, *process.children(recursive=True)]:
                try:
                    cpu, io = p.cpu_times(), p.io_counters()
                    current.append((p.pid, cpu.user + cpu.system, io.read_bytes, io.write_bytes))
                except (psutil.AccessDenied, psutil.NoSuchProcess):
                    pass
            current = tuple(sorted(current))
            if current != metrics:
                metrics, last_activity = current, time.monotonic()
            if time.monotonic() - last_activity > 60:
                raise AssertionError('Inactividad comprobada del workbench de aceptación')
            time.sleep(.2)

    def command(name):
        (ui / (name + '.request')).write_text('', encoding='utf-8')

    try:
        model = client.call('model', 'create', name='Aceptación en vivo', path='vivo.archimate')
        mid = model['modelId']
        created = client.call('batch', 'apply', modelId=mid, operations=[
            {'domain': 'element', 'action': 'create', 'type': 'BusinessActor', 'name': 'Antes', 'as': 'actor'},
            {'domain': 'view', 'action': 'create', 'name': 'Vista abierta', 'as': 'vista'},
            {'domain': 'view', 'action': 'addElement', 'elementId': '$actor.id', 'viewId': '$vista.id', 'x': 30, 'y': 30, 'as': 'visual'},
        ])
        references = created['result']['references']
        actor = references['actor']['id']
        view = references['vista']['id']
        other_id = client.call('model', 'create', name='Segundo modelo visible', path='otro.archimate')['modelId']
        other = client.call('batch', 'apply', modelId=other_id, operations=[
            {'domain': 'element', 'action': 'create', 'type': 'BusinessActor', 'name': 'OtroModelo', 'as': 'actor'},
            {'domain': 'view', 'action': 'create', 'name': 'Segunda vista', 'as': 'vista'},
            {'domain': 'view', 'action': 'addElement', 'elementId': '$actor.id', 'viewId': '$vista.id', 'x': 30, 'y': 30},
        ])['result']['references']
        out = open(evidence / 'workbench.stdout.log', 'w', encoding='utf-8')
        err = open(evidence / 'workbench.stderr.log', 'w', encoding='utf-8')
        startup = subprocess.STARTUPINFO()
        startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        startup.wShowWindow = subprocess.SW_HIDE
        command_line = ['pwsh', '-NoProfile', '-File', str(root / 'tools/start-live-verification.ps1'),
                                '-Model', str(workspace / 'vivo.archimate'), '-ActorId', actor, '-Evidence', str(ui),
                                '-OtherModel', str(workspace / 'otro.archimate'), '-OtherActorId', other['actor']['id']]
        if os.environ.get('AXIOMA_TEST_RUNTIME'):
            command_line += ['-RuntimeHome', os.environ['AXIOMA_TEST_RUNTIME']]
        if os.environ.get('AXIOMA_TEST_LIVE_PACKAGE'):
            command_line += ['-PluginPackage', os.environ['AXIOMA_TEST_LIVE_PACKAGE']]
        gui = subprocess.Popen(command_line,
                               stdout=out, stderr=err, startupinfo=startup)
        idle = wait(lambda s: s.get('liveState') == 'IN_SYNC' and s.get('name') == 'Antes')
        steady = wait(lambda s: int(s.get('observations', '0')) >= int(idle['observations']) + 3)
        assert steady['captures'] == idle['captures'], 'El adaptador copió el modelo sin un cambio de archivo'
        client.call('element', 'update', modelId=mid, id=actor, name='MCPEnVivo')
        first = wait(lambda s: s.get('name') == 'MCPEnVivo' and s.get('liveState') == 'IN_SYNC')
        assert first['sameEditorInstance'] == first['sameViewInstance'] == first['sameModelInstance'] == 'true', first
        assert first['menuActivated'] == first['registryConsistent'] == 'true', first
        if os.environ.get('AXIOMA_TEST_LIVE_PACKAGE'):
            assert first['nativePackageAccepted'] == 'true', first
        assert float(first['zoom']) == 1.25 and first['selection'] == references['visual']['id'], first
        assert 'MCPEnVivo' in first['figureTexts'], first
        command('snapshot')
        wait(lambda s: bool(s.get('screenshot')))
        # También deben aparecer nuevas figuras, no únicamente un texto en el objeto lógico.
        note = client.call('view', 'addNote', modelId=mid, viewId=view, content='NotaEnVivo', x=280, y=30)['result']['id']
        wait(lambda s: 'NotaEnVivo' in s.get('figureTexts', '') and s.get('liveState') == 'IN_SYNC')
        structural = client.call('batch', 'apply', modelId=mid, operations=[
            {'domain': 'element', 'action': 'create', 'type': 'BusinessProcess', 'name': 'ProcesoVivo', 'as': 'proceso'},
            {'domain': 'relationship', 'action': 'create', 'type': 'AssignmentRelationship', 'sourceId': actor, 'targetId': '$proceso.id', 'as': 'rel'},
            {'domain': 'view', 'action': 'addElement', 'elementId': '$proceso.id', 'viewId': view, 'x': 300, 'y': 200, 'as': 'visualProceso'},
            {'domain': 'view', 'action': 'addConnection', 'viewId': view, 'relationshipId': '$rel.id', 'sourceVisualId': references['visual']['id'], 'targetVisualId': '$visualProceso.id'},
            {'domain': 'view', 'action': 'addGroup', 'viewId': view, 'name': 'GrupoVivo', 'x': 20, 'y': 20, 'width': 600, 'height': 400, 'as': 'grupo'},
            {'domain': 'view', 'action': 'moveObject', 'id': references['visual']['id'], 'viewId': view, 'parentId': '$grupo.id', 'x': 30, 'y': 30},
            {'domain': 'view', 'action': 'moveObject', 'id': note, 'viewId': view, 'parentId': '$grupo.id', 'x': 230, 'y': 30},
        ])
        moved = wait(lambda s: 'ProcesoVivo' in s.get('figureTexts', '') and 'GrupoVivo' in s.get('figureTexts', '') and s.get('liveState') == 'IN_SYNC')
        assert moved['registryConsistent'] == 'true', moved
        assert float(moved['zoom']) == 1.25 and moved['selection'] == references['visual']['id'], moved
        client.call('element', 'delete', modelId=mid, id=structural['result']['references']['proceso']['id'])
        removed = wait(lambda s: 'ProcesoVivo' not in s.get('figureTexts', '') and s.get('liveState') == 'IN_SYNC')
        assert removed['registryConsistent'] == 'true', removed
        for number in range(30):
            client.call('element', 'update', modelId=mid, id=actor, name='Serie' + str(number), properties={'serie': str(number)})
        latest = client.call('element', 'update', modelId=mid, id=actor, name='MCPEnVivo')
        wait(lambda s: s.get('fingerprint') == latest['fingerprint'] and s.get('name') == 'MCPEnVivo' and s.get('liveState') == 'IN_SYNC')
        command('human-edit')
        wait(lambda s: s.get('dirty') == 'true' and s.get('name') == 'Edición humana no guardada')
        client.call('element', 'update', modelId=mid, id=actor, name='ExternoEnConflicto')
        external_bytes = (workspace / 'vivo.archimate').read_bytes()
        protected = wait(lambda s: s.get('liveState') == 'CONFLICT_UNSAVED')
        assert protected['name'] == 'Edición humana no guardada' and protected['dirty'] == 'true', protected
        assert (workspace / 'vivo.archimate').read_bytes() == external_bytes
        client.call('element', 'update', modelId=other_id, id=other['actor']['id'], name='OtroMCPEnVivo')
        independent = wait(lambda s: s.get('otherName') == 'OtroMCPEnVivo' and 'OtroMCPEnVivo' in s.get('otherFigureTexts', ''))
        assert independent['name'] == 'Edición humana no guardada' and independent['dirty'] == 'true', independent
        command('undo')
        wait(lambda s: s.get('dirty') == 'false')
        command('resume')
        recovered = wait(lambda s: s.get('name') == 'ExternoEnConflicto' and s.get('liveState') == 'IN_SYNC')
        assert recovered['sameEditorInstance'] == recovered['sameViewInstance'] == 'true', recovered
        assert 'ExternoEnConflicto' in recovered['figureTexts'], recovered
        assert (workspace / 'vivo.archimate').read_bytes() == external_bytes
        # Un archivo ilegible o con otra identidad se rechaza antes de tocar el editor.
        for invalid in [b'archivo nativo invalido', (workspace / 'otro.archimate').read_bytes()]:
            (workspace / 'vivo.archimate').write_bytes(invalid)
            paused = wait(lambda s: s.get('liveState') == 'ERROR_PAUSED', allow_error=True)
            assert paused['name'] == 'ExternoEnConflicto' and paused['dirty'] == 'false', paused
            assert (workspace / 'vivo.archimate').read_bytes() == invalid
            (workspace / 'vivo.archimate').write_bytes(external_bytes)
            command('resume')
            wait(lambda s: s.get('liveState') == 'IN_SYNC', allow_error=True)
        client.call('view', 'delete', modelId=other_id, id=other['vista']['id'])
        wait(lambda s: s.get('otherEditorClosed') == 'true')
        command('snapshot')
        old_screenshot = recovered.get('screenshot')
        wait(lambda s: s.get('screenshot') != old_screenshot)
        command('stop')
        # Limpieza acotada: la operación de negocio ya terminó y no hay cambios humanos pendientes.
        assert gui.wait(timeout=25) == 0
        # El proceso MCP debe seguir editando y exportando después de cerrar completamente la GUI.
        continued = client.call('element', 'update', modelId=mid, id=actor, name='ServidorSinGUI')
        assert continued['persisted'] is True
        assert client.call('runtime', 'info')['workbenchRunning'] is False
        client.call('view', 'export', modelId=mid, viewId=view, format='svg', path='despues-de-cerrar-gui.svg')
        client.close()
        out.close()
        err.close()
        result = {'status': 'PASS', 'realWorkbench': True, 'sameEditor': True, 'sameView': True,
                  'nativeFigureUpdated': True, 'newFigureAdded': True, 'structuralMovesAndDeletes': True, 'nativeMenu': True, 'unsavedHumanChangesProtected': True,
                  'resumeAfterUndo': True, 'guiDidNotWriteModel': True, 'modelsIndependentDuringConflict': True,
                  'invalidFilesProtected': True, 'deletedViewClosed': True, 'idleDoesNotCopyModels': True,
                  'headlessContinuesAfterGuiClose': True, 'rapidWritesCoalesced': True, 'zoomAndSelectionPreserved': True,
                  'liveVersion': first['liveVersion'], 'liveLocation': first['liveLocation'], 'nativePackageAccepted': first['nativePackageAccepted']}
        (evidence / 'resultado.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps({**result, 'evidence': str(evidence)}))
    except Exception:
        print('Evidencia del fallo:', evidence)
        if gui and gui.poll() is None:
            subprocess.run(['taskkill', '/PID', str(gui.pid), '/T', '/F'], capture_output=True)
        if client.process.poll() is None:
            client.process.stdin.close()
            try:
                client.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                subprocess.run(['taskkill', '/PID', str(client.process.pid), '/T', '/F'], capture_output=True)
        raise


if __name__ == '__main__':
    run()
