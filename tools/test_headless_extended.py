"""Aceptación ampliada del metamodelo, archivo nativo, historial y dos procesos MCP."""
import base64
import json
import pathlib
import time
import zipfile
import struct
import zlib
from test_headless import Client


def run():
    root = pathlib.Path(__file__).resolve().parents[1]
    evidence = root / '.axioma-local' / ('ampliada-' + time.strftime('%Y%m%d-%H%M%S'))
    evidence.mkdir(parents=True)
    workspace = evidence / 'modelos'
    client = Client(root, workspace, evidence)
    other = None
    try:
        assert len(client.request('tools/list', {})['tools']) == 9
        native_types = client.call('element', 'types')
        rel_types = client.call('relationship', 'types')
        model = client.call('model', 'create', name='Catálogo completo', path='catalogo.archimate')
        mid = model['modelId']
        operations = [{'domain': 'element', 'action': 'create', 'type': t, 'name': t, 'as': t} for t in native_types]
        made = client.call('batch', 'apply', modelId=mid, operations=operations)
        elements = made['result']['references']
        assert len(client.call('element', 'list', modelId=mid)) == len(native_types)
        assert 'type' in [a['name'] for a in client.call('element', 'schema', type='Junction')['attributes']]
        client.call('element', 'update', modelId=mid, id=elements['Junction']['id'], attributes={'type': 'or'})
        elements['RolEspecializado'] = client.call('element', 'create', modelId=mid, type='BusinessRole', name='Rol especializado')['result']
        pairs = {
            'CompositionRelationship': ('Grouping', 'BusinessActor'),
            'AggregationRelationship': ('Grouping', 'BusinessRole'),
            'AssignmentRelationship': ('BusinessActor', 'BusinessProcess'),
            'RealizationRelationship': ('BusinessProcess', 'BusinessService'),
            'ServingRelationship': ('ApplicationService', 'BusinessProcess'),
            'AccessRelationship': ('BusinessProcess', 'BusinessObject'),
            'InfluenceRelationship': ('Driver', 'Goal'),
            'TriggeringRelationship': ('BusinessProcess', 'ApplicationProcess'),
            'FlowRelationship': ('BusinessProcess', 'ApplicationProcess'),
            'SpecializationRelationship': ('RolEspecializado', 'BusinessRole'),
            'AssociationRelationship': ('BusinessActor', 'Goal'),
        }
        relationships = {}
        for rel_type in rel_types:
            source, target = pairs[rel_type]
            args = {'modelId': mid, 'sourceId': elements[source]['id'], 'targetId': elements[target]['id']}
            valid = client.call('relationship', 'validTypes', **args)
            assert rel_type in valid, (rel_type, source, target, valid)
            relationships[rel_type] = client.call('relationship', 'create', **args, type=rel_type, name=rel_type)['result']
        assert len(client.call('relationship', 'list', modelId=mid)) == len(rel_types)
        boolean_fields = [attr['name'] for attr in client.call('relationship', 'schema', type='AssociationRelationship')['attributes'] if attr['type'] == 'EBoolean']
        assert boolean_fields
        client.call('relationship', 'update', modelId=mid, id=relationships['AssociationRelationship']['id'],
                    attributes={boolean_fields[0]: 'no-es-booleano'}, error='INVALID_ATTRIBUTE_VALUE')
        # Las relaciones también son conceptos y pueden ser extremos de otra relación.
        rel_to_rel = client.call('relationship', 'create', modelId=mid, type='AssociationRelationship',
                                sourceId=relationships['AssignmentRelationship']['id'],
                                targetId=elements['Goal']['id'], name='Traza desde una relación')['result']
        view = client.call('view', 'create', modelId=mid, name='Vista completa')['result']['id']
        second_view = client.call('view', 'create', modelId=mid, name='Vista referenciada')['result']['id']
        group = client.call('view', 'addGroup', modelId=mid, viewId=view, name='Grupo visual', x=20, y=20, width=800, height=400)['result']['id']
        visuals = {}
        for i, (name, element) in enumerate(elements.items()):
            visuals[element['id']] = client.call('view', 'addElement', modelId=mid, viewId=view,
                elementId=element['id'], x=(i % 8) * 200, y=(i // 8) * 100 + 500)['result']['id']
        connections = {}
        for name, rel in relationships.items():
            connections[rel['id']] = client.call('view', 'addConnection', modelId=mid, viewId=view,
                relationshipId=rel['id'], sourceVisualId=visuals[rel['sourceId']], targetVisualId=visuals[rel['targetId']])['result']['id']
        client.call('view', 'addConnection', modelId=mid, viewId=view, relationshipId=rel_to_rel['id'],
                    sourceVisualId=connections[rel_to_rel['sourceId']], targetVisualId=visuals[rel_to_rel['targetId']])
        note = client.call('view', 'addNote', modelId=mid, viewId=view, content='Texto de la nota', parentId=group, x=30, y=30)['result']['id']
        client.call('view', 'moveObject', modelId=mid, id=note, viewId=view, x=900, y=20)
        client.call('view', 'moveObject', modelId=mid, id=group, viewId=view, parentId=group, error='CONTAINER_CYCLE')
        client.call('view', 'addReference', modelId=mid, viewId=view, referencedViewId=second_view, x=30, y=200)
        line = client.call('view', 'addLine', modelId=mid, viewId=view, sourceVisualId=note, targetVisualId=group, name='Línea de apoyo')['result']['id']
        client.call('view', 'setBendpoints', modelId=mid, id=line, bendpoints=[{'startX': 20, 'startY': 30, 'endX': -20, 'endY': -30}])
        client.call('view', 'updateObject', modelId=mid, id=note, attributes={'fillColor': '#ffffff'}, features={'lineStyle': 1})
        client.call('element', 'get', modelId=mid, id=view, error='WRONG_OBJECT_TYPE')
        folders = client.call('folder', 'list', modelId=mid)
        business = next(f for f in folders if f['attributes']['type'].lower() == 'business')
        application = next(f for f in folders if f['attributes']['type'].lower() == 'application')
        folder = client.call('folder', 'create', modelId=mid, parentId=business['id'], name='Actores')['result']['id']
        nested = client.call('folder', 'create', modelId=mid, parentId=folder, name='Subcarpeta')['result']['id']
        client.call('folder', 'move', modelId=mid, id=folder, folderId=nested, error='FOLDER_CYCLE')
        client.call('folder', 'move', modelId=mid, id=nested, folderId=business['id'])
        client.call('folder', 'move', modelId=mid, id=elements['BusinessActor']['id'], folderId=folder)
        client.call('folder', 'move', modelId=mid, id=folder, folderId=application['id'], error='FOLDER_LAYER_MISMATCH')
        profile = client.call('profile', 'create', modelId=mid, type='BusinessActor', name='Operador especializado')['result']['id']
        client.call('profile', 'assign', modelId=mid, id=profile, conceptId=elements['BusinessActor']['id'])
        client.call('profile', 'assign', modelId=mid, id=profile, conceptId=elements['Node']['id'], error='PROFILE_TYPE_MISMATCH')
        # PNG real incorporado por el gestor de archivos de Archi, no una respuesta simulada.
        asset = workspace / 'icono.png'
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
        asset.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0))
                          + chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00\xff')) + chunk(b'IEND', b''))
        client.call('image', 'add', modelId=mid, path='icono.png', error='UNREFERENCED_IMAGE')
        image = client.call('image', 'add', modelId=mid, path='icono.png', id=profile)['result']['imagePath']
        client.call('view', 'addImage', modelId=mid, viewId=view, imagePath=image, x=1100, y=20)
        assert image in client.call('image', 'list', modelId=mid)
        old = client.call('model', 'info', modelId=mid)['fingerprint']
        client.call('model', 'update', modelId=mid, name='Cambio recuperable')
        assert any(item['fingerprint'] == old for item in client.call('model', 'history', modelId=mid))
        client.call('model', 'restore', modelId=mid, fingerprint=old)
        assert client.call('model', 'info', modelId=mid)['name'] == 'Catálogo completo'
        assert image in client.call('image', 'list', modelId=mid)
        # Segundo proceso real y misma raíz: no debe existir modelo activo compartido.
        other = Client(root, workspace, evidence)
        other.call('model', 'open', path='catalogo.archimate')
        client.call('model', 'update', modelId=mid, name='Edición del primer proceso')
        other.call('model', 'update', modelId=mid, name='No sobrescribir', error='EXTERNAL_FILE_CONFLICT')
        other.call('model', 'reload', modelId=mid)
        other.call('model', 'update', modelId=mid, name='Edición del segundo proceso')
        other.close()
        other = None
        client.call('model', 'reload', modelId=mid)
        assert client.call('model', 'info', modelId=mid)['name'] == 'Edición del segundo proceso'
        assert client.call('model', 'validate', modelId=mid)['errors'] == []
        client.call('view', 'export', modelId=mid, viewId=view, path='completo.svg', format='svg')
        client.call('model', 'close', modelId=mid)
        client.call('model', 'open', path='catalogo.archimate')
        assert image in client.call('image', 'list', modelId=mid)
        with zipfile.ZipFile(workspace / 'catalogo.archimate') as archive:
            assert any('images/' in name for name in archive.namelist()), archive.namelist()
            assert archive.read(image) == asset.read_bytes(), 'La imagen nativa no conserva sus bytes originales'
        # Borrado transitivo: elimina representación y relaciones que dependan de un concepto.
        client.call('element', 'delete', modelId=mid, id=elements['BusinessActor']['id'])
        assert client.call('model', 'validate', modelId=mid)['errors'] == []
        client.call('view', 'delete', modelId=mid, id=second_view)
        assert client.call('model', 'validate', modelId=mid)['errors'] == []
        client.call('model', 'create', name='Ensayo', path='sin-guardar.archimate', dryRun=True, error='DRY_RUN_UNSUPPORTED')
        assert not (workspace / 'sin-guardar.archimate').exists()
        (workspace / 'corrupto.archimate').write_text('esto no es XML', encoding='utf-8')
        client.call('model', 'open', path='corrupto.archimate', error='MODEL_LOAD_FAILED')
        client.close()
        result = {'status': 'PASS', 'nativeElementTypes': len(native_types), 'nativeRelationshipTypes': len(rel_types),
                  'relationshipsAsEndpoints': True, 'historyRestore': True, 'imagesPreserved': True, 'twoProcesses': True}
        (evidence / 'resultado.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps({**result, 'evidence': str(evidence)}))
    except Exception:
        print('Evidencia del fallo:', evidence)
        for c in [other, client]:
            if c and c.process.poll() is None:
                c.process.stdin.close()
                try:
                    c.process.wait(timeout=15)
                except Exception:
                    import subprocess
                    subprocess.run(['taskkill', '/PID', str(c.process.pid), '/T', '/F'], capture_output=True)
        raise


if __name__ == '__main__':
    run()
