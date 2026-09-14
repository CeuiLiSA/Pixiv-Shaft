/* Shared preview controls. No account or API calls. */
window.TabletPreview = (() => {
  const root = document.documentElement;
  root.dataset.theme = 'light';
  root.dataset.accent = 'violet';
  let contentState = 'normal';
  const control = (type, value, text) => `<button class="v3-button tonal" data-preview-${type}="${value}" aria-pressed="${type === 'state' ? contentState === value : root.dataset[type] === value}">${text}</button>`;
  function controls() {
    return `<div class="preview-controls"><h3>主题</h3><div class="v3-actions">${[['light','浅色'],['dark','深色']].map(([v,t])=>control('theme',v,t)).join('')}</div><h3>强调色</h3><div class="v3-actions">${[['violet','紫色'],['rose','玫瑰'],['teal','青绿']].map(([v,t])=>control('accent',v,t)).join('')}</div><h3>内容状态</h3><div class="v3-actions">${[['normal','正常'],['loading','加载中'],['error','加载失败']].map(([v,t])=>control('state',v,t)).join('')}</div></div>`;
  }
  function setState(value) {
    contentState = value;
    document.dispatchEvent(new CustomEvent('previewstate', {detail:value}));
  }
  function syncControls() {
    document.querySelectorAll('[data-preview-theme],[data-preview-accent],[data-preview-state]').forEach(button => {
      const type = ['theme','accent','state'].find(t => button.hasAttribute(`data-preview-${t}`));
      button.setAttribute('aria-pressed', button.getAttribute(`data-preview-${type}`) === (type === 'state' ? contentState : root.dataset[type]));
    });
  }
  document.addEventListener('click', event => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.previewTheme) root.dataset.theme = button.dataset.previewTheme;
    if (button.dataset.previewAccent) root.dataset.accent = button.dataset.previewAccent;
    if (button.dataset.previewState) setState(button.dataset.previewState);
    if (button.hasAttribute('data-preview-retry')) setState('normal');
    syncControls();
  });
  function toggleTheme() { root.dataset.theme = root.dataset.theme === 'dark' ? 'light' : 'dark'; syncControls(); }
  function status(host) {
    const active = contentState !== 'normal';
    host.hidden = !active;
    host.dataset.kind = contentState;
    host.innerHTML = contentState === 'loading'
      ? '<h2>正在加载作品</h2><p>加载状态示例</p><div class="skeleton-grid" aria-hidden="true"><span></span><span></span><span></span></div><button class="v3-button tonal" data-preview-retry>结束加载演示</button>'
      : '<h2>作品加载失败</h2><p>错误状态示例，可重试恢复内容。</p><button class="v3-button" data-preview-retry>重试</button>';
    return active;
  }
  return {controls, toggleTheme, status, setState};
})();
