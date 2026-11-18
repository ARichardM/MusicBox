/* Las portadas de googleusercontent.com rechazan las peticiones del navegador que llevan el origen de la web
   (se ven rotas). Se piden sin referrer solo ellas, no la página entera, para no afectar a Firebase. */
(() => {
  const remote = (image) => {
    const source = image.getAttribute('src') || '';
    if (!/^https?:\/\//i.test(source)) return false;
    try { return new URL(source).origin !== location.origin; } catch { return false; }
  };
  const protect = (image) => { if (remote(image) && image.referrerPolicy !== 'no-referrer') image.referrerPolicy = 'no-referrer'; };
  const scan = (node) => {
    if (node.nodeType !== 1) return;
    if (node.tagName === 'IMG') protect(node);
    node.querySelectorAll?.('img').forEach(protect);
  };
  new MutationObserver((records) => {
    for (const record of records) {
      if (record.type === 'attributes') protect(record.target);
      else record.addedNodes.forEach(scan);
    }
  }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
  // Si una imagen ya empezó a cargar con referrer y falla, se reintenta una sola vez sin él.
  document.addEventListener('error', (event) => {
    const image = event.target;
    if (!(image instanceof HTMLImageElement) || !remote(image) || image.dataset.noReferrerRetry) return;
    image.dataset.noReferrerRetry = '1';
    image.referrerPolicy = 'no-referrer';
    image.src = image.getAttribute('src');
  }, true);
})();
