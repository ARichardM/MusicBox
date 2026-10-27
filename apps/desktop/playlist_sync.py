"""Mezcla de tres vías: solo se eliminan entradas que el dispositivo había conocido.

Una copia antigua no puede borrar adiciones que llegaron desde otro equipo. Las
eliminaciones intencionales de entradas presentes en la base sí se sincronizan.
"""
from copy import deepcopy

_MISSING = object()


def _merge(base, local, remote):
    if local == base:
        return remote
    if remote == base:
        return local
    if local is _MISSING:
        return remote
    if remote is _MISSING:
        return local
    if isinstance(local, dict) and isinstance(remote, dict):
        before = base if isinstance(base, dict) else {}
        result = {}
        for key in dict.fromkeys([*remote, *local, *before]):
            value = _merge(before.get(key, _MISSING), local.get(key, _MISSING), remote.get(key, _MISSING))
            if value is not _MISSING:
                result[key] = value
        return result
    if isinstance(local, list) and isinstance(remote, list):
        before = base if isinstance(base, list) else []
        removed = {entry for entry in before if entry not in local or entry not in remote}
        return list(dict.fromkeys(entry for entry in [*local, *remote] if entry not in removed))
    return local


def merge_playlists(base, local, remote):
    return deepcopy(_merge(base or {}, local or {}, remote or {}))


def append_audit(root, previous, current, device, platform, now):
    before = (previous or {}).get("playlists", {})
    after = (current or {}).get("playlists", {})
    changes = []
    for name in sorted(set(before) | set(after)):
        old, new = before.get(name, []), after.get(name, [])
        if old == new and (name in before) == (name in after):
            continue
        changes.append({"name": name, "before": len(old), "after": len(new),
                        "added": len(set(new) - set(old)), "removed": len(set(old) - set(new))})
    if changes:
        history = root.get("playlistAudit")
        history = history if isinstance(history, list) else []
        root["playlistAudit"] = (history + [{"at": now, "device": device, "platform": platform, "changes": changes}])[-30:]
