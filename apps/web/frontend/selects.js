/*
 * Selectores desplegables con el mismo aspecto y comportamiento que los del escritorio (Combo.qml): campo redondeado con
 * flecha que gira, lista flotante con opciones de 38 px y la elegida en negrita. El <select> nativo se conserva oculto
 * (valor, eventos y accesibilidad) y el botón lo refleja.
 */
(function () {
  let openSelect = null;
  const reduced = () => matchMedia('(prefers-reduced-motion: reduce)').matches;

  function close(restoreFocus = true) {
    if (!openSelect) return;
    const { button, popup, onOutside, onKey, onScroll } = openSelect;
    document.removeEventListener('pointerdown', onOutside, true);
    document.removeEventListener('keydown', onKey, true);
    window.removeEventListener('resize', onScroll);
    document.removeEventListener('scroll', onScroll, true);
    button.setAttribute('aria-expanded', 'false');
    button.parentElement.classList.remove('open');
    popup.classList.remove('shown');
    const gone = () => popup.remove();
    if (reduced()) gone(); else setTimeout(gone, 140);
    if (restoreFocus) button.focus({ preventScroll: true });
    openSelect = null;
  }

  function labelOf(select) {
    const option = select.selectedOptions[0];
    return option ? option.textContent : '';
  }

  function enhance(select) {
    if (select.dataset.mbSelect || select.multiple || select.closest('.mb-select')) return;
    select.dataset.mbSelect = '1';
    const wrap = document.createElement('div');
    wrap.className = 'mb-select' + (select.className ? ' ' + select.className.split(/\s+/).map((c) => 'mb-' + c).join(' ') : '');
    if (/sort/.test(select.className + select.id)) wrap.classList.add('mb-sort');
    select.before(wrap);
    wrap.append(select);
    select.classList.add('mb-native');
    select.tabIndex = -1;
    select.setAttribute('aria-hidden', 'true');
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'mb-select-button';
    button.setAttribute('aria-haspopup', 'listbox');
    button.setAttribute('aria-expanded', 'false');
    const label = select.getAttribute('aria-label') || (select.id && document.querySelector(`label[for="${select.id}"]`)?.textContent) || '';
    if (label) button.setAttribute('aria-label', label);
    button.innerHTML = '<span class="mb-select-icon" aria-hidden="true"></span><span class="mb-select-text"></span><svg class="mb-select-chevron" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M9 6l6 6-6 6" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    wrap.append(button);
    const text = button.querySelector('.mb-select-text');
    const refresh = () => { text.textContent = labelOf(select); wrap.classList.toggle('mb-disabled', select.disabled); button.disabled = select.disabled; };
    refresh();
    new MutationObserver(refresh).observe(select, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled'] });
    select.addEventListener('change', refresh);
    // El código de la aplicación asigna `select.value = …` sin disparar eventos: se refleja también.
    const proto = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
    Object.defineProperty(select, 'value', { get() { return proto.get.call(this); }, set(v) { proto.set.call(this, v); refresh(); } });

    const pick = (index) => {
      if (select.selectedIndex !== index) { select.selectedIndex = index; select.dispatchEvent(new Event('input', { bubbles: true })); select.dispatchEvent(new Event('change', { bubbles: true })); }
      refresh(); close();
    };

    const open = () => {
      if (select.disabled) return;
      if (openSelect) { const same = openSelect.button === button; close(false); if (same) return; }
      const popup = document.createElement('ul');
      popup.className = 'mb-select-popup';
      popup.setAttribute('role', 'listbox');
      let active = select.selectedIndex;
      [...select.options].forEach((option, index) => {
        const item = document.createElement('li');
        item.setAttribute('role', 'option');
        item.className = 'mb-select-item' + (index === select.selectedIndex ? ' current' : '');
        item.setAttribute('aria-selected', String(index === select.selectedIndex));
        item.textContent = option.textContent;
        item.disabled = option.disabled;
        item.addEventListener('pointerenter', () => setActive(index));
        item.addEventListener('click', () => { if (!option.disabled) pick(index); });
        popup.append(item);
      });
      const items = () => [...popup.children];
      const setActive = (index) => { active = Math.max(0, Math.min(select.options.length - 1, index)); items().forEach((el, i) => el.classList.toggle('active', i === active)); items()[active]?.scrollIntoView({ block: 'nearest' }); };
      document.body.append(popup);
      const place = () => {
        const rect = button.getBoundingClientRect();
        popup.style.minWidth = Math.max(rect.width, 150) + 'px';
        const height = popup.offsetHeight, width = popup.offsetWidth;
        const below = innerHeight - rect.bottom - 12, above = rect.top - 12;
        const up = below < Math.min(height, 240) && above > below;
        popup.style.maxHeight = Math.max(120, (up ? above : below)) + 'px';
        popup.style.top = (up ? Math.max(8, rect.top - Math.min(height, above) - 6) : rect.bottom + 6) + 'px';
        popup.style.left = Math.max(8, Math.min(innerWidth - width - 8, rect.right - width > 8 && /sort/.test(wrap.className) ? rect.right - width : rect.left)) + 'px';
        popup.classList.toggle('up', up);
      };
      place();
      requestAnimationFrame(() => popup.classList.add('shown'));
      button.setAttribute('aria-expanded', 'true');
      wrap.classList.add('open');
      setActive(active);
      const onOutside = (event) => { if (!popup.contains(event.target) && !button.contains(event.target)) close(false); };
      const onKey = (event) => {
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); }
        else if (event.key === 'ArrowDown') { event.preventDefault(); setActive(active + 1); }
        else if (event.key === 'ArrowUp') { event.preventDefault(); setActive(active - 1); }
        else if (event.key === 'Home') { event.preventDefault(); setActive(0); }
        else if (event.key === 'End') { event.preventDefault(); setActive(select.options.length - 1); }
        else if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); pick(active); }
        else if (event.key === 'Tab') close(false);
        else if (event.key.length === 1) { const i = [...select.options].findIndex((o, n) => n > active && o.textContent.toLowerCase().startsWith(event.key.toLowerCase())); if (i >= 0) setActive(i); }
      };
      const onScroll = () => place();
      document.addEventListener('pointerdown', onOutside, true);
      document.addEventListener('keydown', onKey, true);
      window.addEventListener('resize', onScroll);
      document.addEventListener('scroll', onScroll, true);
      openSelect = { button, popup, onOutside, onKey, onScroll };
    };
    button.addEventListener('click', open);
    button.addEventListener('keydown', (event) => {
      if (openSelect && openSelect.button === button) return; // con la lista abierta, las flechas mueven la selección
      if (['ArrowDown', 'ArrowUp'].includes(event.key)) { event.preventDefault(); open(); }
    });
  }

  function scan(root) {
    if (root.nodeType !== 1 && root.nodeType !== 9) return;
    (root.matches?.('select') ? [root] : []).concat([...root.querySelectorAll?.('select') || []]).forEach(enhance);
  }
  document.addEventListener('DOMContentLoaded', () => {
    scan(document);
    new MutationObserver((records) => { for (const record of records) record.addedNodes.forEach((node) => { if (node.nodeType === 1) scan(node); }); })
      .observe(document.body, { childList: true, subtree: true });
  });
})();
