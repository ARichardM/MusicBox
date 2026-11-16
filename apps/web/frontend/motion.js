/*
 * El reproductor a pantalla completa se despliega y se pliega con animación: la barra inferior se transforma en la vista
 * grande (y al revés) con la API de transiciones de vista; sin ella, o con movimiento reducido, el cambio es inmediato.
 */
(function () {
  const TOKEN = 'player-expanded';
  const list = document.body.classList;
  const original = { add: list.add.bind(list), remove: list.remove.bind(list), toggle: list.toggle.bind(list) };
  let running = false;

  function animated(change) {
    if (running || !document.startViewTransition || matchMedia('(prefers-reduced-motion: reduce)').matches) { change(); return; }
    running = true;
    document.documentElement.classList.add('player-morph');
    const transition = document.startViewTransition(() => { change(); });
    const done = () => { running = false; document.documentElement.classList.remove('player-morph'); };
    transition.finished.then(done, done);
  }

  const has = () => list.contains(TOKEN);
  list.add = function (...tokens) {
    if (tokens.includes(TOKEN) && !has()) { animated(() => original.add(...tokens)); return; }
    original.add(...tokens);
  };
  list.remove = function (...tokens) {
    if (tokens.includes(TOKEN) && has()) { animated(() => original.remove(...tokens)); return; }
    original.remove(...tokens);
  };
  list.toggle = function (token, force) {
    if (token !== TOKEN) return original.toggle(token, force);
    const next = force === undefined ? !has() : !!force;
    if (next === has()) return next;
    animated(() => original.toggle(token, next));
    return next;
  };
})();
