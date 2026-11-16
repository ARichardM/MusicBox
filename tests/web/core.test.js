const { test } = require('node:test');
const assert = require('node:assert/strict');
const { merge, section, track, escape } = require('../../apps/web/frontend/core.js');
const {mergeStats} = require('../../apps/web/frontend/core.js');
const {ListeningClock} = require('../../apps/web/frontend/core.js');

test('qualified plays need 30 real seconds and exclude pauses, stalls and seeks', () => {
  const clock=new ListeningClock();
  clock.sample('first',0,true,0);
  for(let i=1;i<30;i++) assert.equal(clock.sample('first',i*1000,true,i*1000).qualified,false);
  assert.equal(clock.sample('first',30000,true,30000).qualified,true);
  assert.equal(clock.sample('first',31000,false,31000).qualified,false);
  assert.equal(clock.sample('first',31000,true,60000).millis,0);
  assert.equal(clock.sample('first',31000,true,61000).millis,0);
  assert.equal(clock.sample('first',100000,true,62000).millis,0);
  assert.deepEqual(clock.sample('second',0,true,63000),{millis:0,qualified:false});
  assert.equal(clock.elapsed,0);
});

test('reads Android/Windows IDs and channel metadata', () => {
  assert.equal(track('youtube:abcdefghijk', { abcdefghijk: { title:'Song', channel:'Artist' } }).artist, 'Artist');
  assert.equal(track('C:\\Music\\local.mp3'), null);
});
test('exports IDs, keeps unavailable entries and metadata owned by other platforms', () => {
  const result = section([{ name:'Mix', tracks:[{id:'abcdefghijk', title:'Song', artist:'Artist'}], unavailableEntries:['C:\\local.mp3'], pinned:true,autoDownload:true }], [], { autoDownload:['Mix'], other:{a:1} });
  assert.deepEqual(result.playlists.Mix, ['youtube:abcdefghijk','C:\\local.mp3']);
  assert.equal(result.onlineTracks.abcdefghijk.channel, 'Artist');
  assert.deepEqual(result.autoDownload, ['Mix']);
  assert.deepEqual(result.pinnedPlaylists, ['Mix']);
  assert.deepEqual(result.other, {a:1});
});
test('stale browser preserves concurrent additions on another device', () => {
  const base = {playlists:{Mix:['youtube:a']}};
  const local = {playlists:{Mix:['youtube:a','youtube:b']}};
  const remote = {playlists:{Mix:['youtube:a','youtube:c'],New:['youtube:d']}, descriptions:{New:'Keep me'}};
  const result = merge(base,local,remote);
  assert.deepEqual(result.playlists.Mix,['youtube:a','youtube:b','youtube:c']);
  assert.deepEqual(result.playlists.New,['youtube:d']);
  assert.equal(result.descriptions.New,'Keep me');
});
test('intentional removal deletes only known entries', () => {
  assert.deepEqual(merge(['a','b'],['b'],['a','b','c']), ['b','c']);
  assert.deepEqual(merge({Mix:['a']},{},{Mix:['a']}), {});
});
test('remote deletion survives an unchanged stale copy', () => {
  assert.deepEqual(merge({Mix:['a']},{Mix:['a']},{}), {});
});
test('HTML values cannot inject tags or attribute boundaries', () => {
  assert.equal(escape('<img src=x onerror="alert(1)">'), '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;');
});
test('browser files never become fake YouTube IDs', () => {
  const result = section([], [{id:'local:sample.mp3',title:'Local'}]);
  assert.deepEqual(result.playlists.Favoritos,['local:sample.mp3']);
  assert.deepEqual(result.onlineTracks,{});
});
test('shared play counters take the maximum per device without double counting', () => {
  const a={devices:{web:{'yt:a':2},windows:{'yt:a':3}},listening:{web:{'yt:a':{ms:1000,plays:2,title:'Song'}}}};
  const b={devices:{web:{'yt:a':1},android:{'yt:a':4}},listening:{web:{'yt:a':{ms:500,plays:1}}}};
  const merged=mergeStats(a,b);
  assert.deepEqual(merged.devices,{web:{'yt:a':2},windows:{'yt:a':3},android:{'yt:a':4}});
  assert.equal(merged.listening.web['yt:a'].ms,1000);
  assert.deepEqual(mergeStats(merged,merged),merged);
});
