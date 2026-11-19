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
        # Conflicting orders must converge regardless of which device is local.
        first, second = sorted((local, remote))
        return list(dict.fromkeys(entry for entry in [*first, *second] if entry not in removed))
    return local


def merge_playlists(base, local, remote):
    result = deepcopy(_merge(base or {}, local or {}, remote or {}))
    if any("entryAddedAt" in (source or {}) for source in (base, local, remote)):
        dates = {}
        for name, entries in result.get("playlists", {}).items():
            dates[name] = {key: max((source or {}).get("entryAddedAt", {}).get(name, {}).get(key, 0)
                                   for source in (base, local, remote)) for key in entries}
        result["entryAddedAt"] = dates
    return result


def entry_dates(playlists, dates=None, previous=None, now=0):
    """Preserve legacy order; only new memberships receive the current time."""
    dates, previous = dates or {}, playlists if previous is None else previous
    result = {}
    for name, entries in playlists.items():
        known = dates.get(name, {})
        old = previous.get(name, [])
        result[name] = {key: known.get(key) or (old.index(key) + 1 if key in old else now or index + 1)
                        for index, key in enumerate(entries)}
    return result


def merge_activity(left=None, right=None):
    """Portable, bounded history: IDs and metadata, never a device's file paths."""
    left, right = left or {}, right or {}
    result = {}
    for kind, limit in (("queries", 8), ("played", 50)):
        cleared = max(left.get(kind + "ClearedAt", 0), right.get(kind + "ClearedAt", 0))
        rows = {}
        for source in (left.get(kind, {}), right.get(kind, {})):
            for key, value in source.items():
                stamp = value.get("at", 0) if isinstance(value, dict) else value
                old = rows.get(key, {})
                old_stamp = old.get("at", 0) if isinstance(old, dict) else old
                if stamp > cleared and stamp >= old_stamp:
                    rows[key] = deepcopy(value)
        stamp = lambda key: -(rows[key].get("at", 0) if isinstance(rows[key], dict) else rows[key])
        result[kind] = {key: rows[key] for key in sorted(rows, key=lambda key: (stamp(key), key))[:limit]}
        result[kind + "ClearedAt"] = cleared
    return result


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
