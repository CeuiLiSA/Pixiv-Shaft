/* Design-system demonstrations only. Replace these actions with real state in product pages. */
'use strict';
const root = document.documentElement;
const dialog = document.querySelector('#demo-dialog');
let trigger;
const roleNames = ['bg', 'surface', 'surface-2', 'ink', 'muted', 'primary', 'primary-ink', 'tint', 'on-tint', 'line', 'green', 'hero'];

function refreshTheme() {
  document.querySelectorAll('[data-toggle-theme]').forEach(button => {
    const label = root.dataset.theme === 'dark' ? '切换浅色' : '切换深色';
    if (!button.classList.contains('v3-row')) button.textContent = label;
    button.setAttribute('aria-label', label);
  });
  document.querySelectorAll('button[data-accent]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.accent === root.dataset.accent)));
  const swatches = document.querySelector('#swatches');
  if (swatches) {
    const style = getComputedStyle(root);
    swatches.replaceChildren(...roleNames.map(name => {
      const card = document.createElement('article');
      card.className = 'swatch';
      const color = document.createElement('div');
      color.className = 'swatch-color';
      color.style.background = `var(--${name})`;
      color.setAttribute('aria-hidden', 'true');
      const title = document.createElement('code');
      title.textContent = `--${name}`;
      const value = document.createElement('small');
      value.textContent = style.getPropertyValue(`--${name}`).trim();
      card.append(color, title, value);
      return card;
    }));
  }
}

function showState(state) {
  const panel = document.querySelector('#state-preview');
  if (!panel) return;
  const states = {
    empty: ['这里还没有任务', '空状态解释原因，给用户一个有意义的下一步。', '查看页面模板', ''],
    loading: ['正在加载任务', '演示加载状态：此处没有发起请求，也不显示虚构的进度。', '', 'info'],
    error: ['暂时无法加载', '保留用户上下文，说明可以重试；不要把网络失败显示为空列表。', '模拟重试', 'error'],
    pending: ['推荐内容已提交', '演示审核状态：说明预计反馈时间，并提供查看记录的入口。', '查看示例记录', 'info'],
    success: ['奖励已放进卡包', '演示成功状态：说明结果在哪里，以及是否还有下一步。', '查看示例结果', 'success']
  };
  const [title, text, action, tone] = states[state];
  panel.innerHTML = `<article class="v3-empty"><span class="v3-status ${tone}">${state === 'success' ? '✓ 已完成' : state === 'error' ? '! 加载失败' : state === 'loading' ? '◷ 加载中' : state === 'pending' ? '◷ 审核中' : '空状态'}</span><h3 style="margin-top:16px">${title}</h3><p>${text}</p>${action ? state === 'empty' ? `<a class="v3-button" href="starter.html">${action} →</a>` : `<button class="v3-button" ${state === 'error' ? 'data-retry-demo' : 'data-open-dialog'}>${action} →</button>` : ''}</article>`;
  document.querySelectorAll('[data-state]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.state === state)));
}

document.addEventListener('click', event => {
  const button = event.target.closest('button');
  if (!button || button.disabled) return;
  if (button.hasAttribute('data-toggle-theme')) {
    root.dataset.theme = root.dataset.theme === 'dark' ? 'light' : 'dark';
    refreshTheme();
  }
  if (button.dataset.accent) { root.dataset.accent = button.dataset.accent; refreshTheme(); }
  if (button.dataset.state) showState(button.dataset.state);
  if (button.hasAttribute('data-retry-demo')) showState('success');
  if (button.hasAttribute('data-open-dialog') && dialog) {
    trigger = button;
    dialog.querySelector('.v3-feedback').textContent = '';
    dialog.showModal();
  }
  if (button.hasAttribute('data-close-dialog')) dialog?.close();
  if (button.hasAttribute('data-confirm-demo')) dialog.querySelector('.v3-feedback').textContent = '示例操作完成。实际页面需在业务请求成功后更新状态。';
  if (button.dataset.preview) button.closest('.v3-card').querySelector('.v3-feedback').textContent = button.dataset.preview;
});
dialog?.addEventListener('close', () => { if (trigger?.isConnected) trigger.focus(); });
refreshTheme();
showState('empty');
