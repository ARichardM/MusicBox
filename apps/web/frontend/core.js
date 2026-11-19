/* Shared MusicBox identities and three-way cloud merge. No Firebase dependency. */
(function (root) {
  const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
  const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const object = value => value && typeof value === 'object' && !Array.isArray(value);
  function merge(base, local, remote) {
    if (equal(local, base)) return clone(remote);
    if (equal(remote, base)) return clone(local);
    if (local === undefined) return clone(remote);
    if (remote === undefined) return clone(local);
    if (Array.isArray(local) && Array.isArray(remote)) {
      const before = Array.isArray(base) ? base : [];
      const removed = new Set(before.filter(x => !local.includes(x) || !remote.includes(x)));
      const compare = (a,b) => { for(let i=0;i<Math.min(a.length,b.length);i++){if(a[i]!==b[i])return a[i]<b[i]?-1:1;}return a.length-b.length; };
      const [first,second]=[local,remote].sort(compare);
      return [...new Set([...first, ...second])].filter(x => !removed.has(x));
    }
    if (object(local) && object(remote)) {
      const result = {};
      for (const key of new Set([...Object.keys(remote), ...Object.keys(local), ...Object.keys(base || {})])) {
        if (['__proto__', 'constructor', 'prototype'].includes(key)) continue;
        let value = merge(base?.[key], local[key], remote[key]);
        if(key==='entryAddedAt'){
          value={};
          for(const source of [base?.[key]||{},local[key]||{},remote[key]||{}])for(const [name,dates] of Object.entries(source)){
            value[name]||={};for(const [entry,at] of Object.entries(dates))value[name][entry]=Math.max(value[name][entry]||0,Number(at)||0);
          }
        }
        if (value !== undefined) result[key] = value;
      }
      return result;
    }
    return clone(local);
  }
  function track(entry, online = {}) {
    if (object(entry)) return { ...entry, artist: entry.artist || entry.channel || entry.subtitle || '' };
    if (typeof entry !== 'string') return null;
    const id = entry.startsWith('youtube:') ? entry.slice(8) : (online[entry] ? entry : '');
    if (!id) return null; // A Windows path cannot be played by a browser.
    const meta = online[id] || online['youtube:' + id] || {};
    return { ...meta, id, title: meta.title || id, artist: meta.artist || meta.channel || meta.subtitle || '',
      thumbnail: meta.thumbnail || `https://i.ytimg.com/vi/${id}/hqdefault.jpg` };
  }
  function section(playlists, favorites, base = {}) {
    const result = clone(base);
    result.playlists = {};
    result.onlineTracks = { ...(base.onlineTracks || {}) };
    result.descriptions = { ...(base.descriptions || {}) };
    result.pinnedPlaylists = [];
    result.playlistOrder = [];
    result.entryAddedAt = clone(base.entryAddedAt || {});
    result.autoDownload = (base.autoDownload || []).filter(name => name === 'Favoritos');
    const encode = tracks => tracks.map(item => {
      if (typeof item === 'string') return item;
      if (!item?.id) return null;
      if (item.id.startsWith('local:')) return item.id;
      result.onlineTracks[item.id] = { title: item.title, channel: item.artist || item.channel || '',
        duration: item.duration || 0, thumbnail: item.thumbnail || '', url: item.url || `https://www.youtube.com/watch?v=${item.id}` };
      return 'youtube:' + item.id;
    }).filter(Boolean);
    const favoriteKeys=encode(favorites);
    const oldFavorites=base.playlists?.Favoritos || [];
    result.playlists.Favoritos = [...oldFavorites.filter(key=>favoriteKeys.includes(key)),...favoriteKeys.filter(key=>!oldFavorites.includes(key))];
    result.entryAddedAt.Favoritos = {...result.entryAddedAt.Favoritos};
    for(const item of favorites) if(item?.id && item.addedAt) result.entryAddedAt.Favoritos['youtube:'+item.id]=item.addedAt;
    for (const pl of playlists) {
      if (pl.name === 'Favoritos') continue;
      const available=encode(pl.tracks || []), hidden=new Set(pl.unavailableEntries || []);
      const original=pl.cloudEntries || base.playlists?.[pl.name] || [];
      // Hidden files keep their slots when the browser saves an unchanged playlist.
      const remaining=[...available];
      const restored=original.flatMap(key=>hidden.has(key)?[key]:remaining.length?[remaining.shift()]:[]);
      result.playlists[pl.name] = [...new Set([...restored,...remaining,...hidden])];
      result.entryAddedAt[pl.name]={...result.entryAddedAt[pl.name],...pl.entryAddedAt};
      result.descriptions[pl.name] = pl.description || '';
      result.playlistOrder.push(pl.name);
      if (pl.pinned) result.pinnedPlaylists.push(pl.name);
      if (pl.autoDownload) result.autoDownload.push(pl.name);
    }
    // Preserve local-only favorites received from another device.
    for (const key of base.playlists?.Favoritos || []) {
      if (typeof key === 'string' && !track(key, result.onlineTracks)) result.playlists.Favoritos.push(key);
    }
    for(const [name,entries] of Object.entries(result.playlists)){
      const known=result.entryAddedAt[name] || {},old=base.playlists?.[name] || [];
      result.entryAddedAt[name]=Object.fromEntries(entries.map((key,index)=>[key,known[key] || (old.includes(key)?old.indexOf(key)+1:Date.now()+index)]));
    }
    for(const name of Object.keys(result.entryAddedAt)) if(!result.playlists[name])delete result.entryAddedAt[name];
    return result;
  }
  function mergeActivity(left={},right={}) {
    const result={};
    for(const [kind,limit] of [['queries',8],['played',50]]){
      const cleared=Math.max(left[kind+'ClearedAt']||0,right[kind+'ClearedAt']||0),rows={};
      for(const source of [left[kind]||{},right[kind]||{}])for(const [key,value] of Object.entries(source)){
        const at=typeof value==='object'?value.at:value,old=rows[key];
        if(at>cleared&&at>=(typeof old==='object'?old.at:old||0))rows[key]=clone(value);
      }
      const at=key=>typeof rows[key]==='object'?rows[key].at:rows[key];
      result[kind]=Object.fromEntries(Object.keys(rows).sort((a,b)=>at(b)-at(a)||(a<b?-1:a>b?1:0)).slice(0,limit).map(key=>[key,rows[key]]));
      result[kind+'ClearedAt']=cleared;
    }
    return result;
  }
  function escape(value) {
    return String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  }
  function mergeStats(remote = {}, local = {}) {
    const maximum = (a,b) => {
      if (typeof a === 'number' || typeof b === 'number') return Math.max(Number(a) || 0, Number(b) || 0);
      if (object(a) || object(b)) {
        const result = {};
        for (const key of new Set([...Object.keys(a || {}), ...Object.keys(b || {})])) {
          if (['__proto__','constructor','prototype'].includes(key)) continue;
          result[key] = maximum(a?.[key],b?.[key]);
        }
        return result;
      }
      return b || a;
    };
    return { ...remote, ...local, v:1, titles:{...remote.titles,...local.titles},
      devices:maximum(remote.devices,local.devices),daily:maximum(remote.daily,local.daily),listening:maximum(remote.listening,local.listening) };
  }
  class ListeningClock {
    constructor() { this.reset(); }
    reset() { this.previous=null; this.elapsed=0; this.counted=false; }
    sample(key, position, playing, now) {
      const previous=this.previous;
      if (previous && previous.key !== key) this.reset();
      let millis=0, qualified=false;
      if (previous?.key === key && previous.playing) {
        const wall=now-previous.now, delta=position-previous.position;
        if (wall>0 && delta>0 && Math.abs(delta-wall)<1200) {
          millis=Math.min(delta,wall);
          this.elapsed+=millis;
          if (!this.counted && this.elapsed>=30000) { this.counted=true; qualified=true; }
        }
      }
      this.previous={key,position,playing,now};
      return {millis,qualified};
    }
  }
  const api = { merge, track, section, escape, mergeStats, mergeActivity, ListeningClock };
  root.MusicBoxCore = api;
  if (typeof module !== 'undefined') module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : window);
