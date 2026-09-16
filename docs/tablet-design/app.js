'use strict';
const $ = selector => document.querySelector(selector);
const icon = name => `<svg aria-hidden="true"><use href="#${name}"/></svg>`;
const state = {route:'推荐', tag:'全部', tab:'全部', query:'', selected:null, saved:new Set(), queue:[]};
const positions = new Map();
let toastTimer, detailOrigin, viewerOrigin, infoOrigin;
let sequence = [];
const headings = {
  推荐: ['ARTWORK FEED', '推荐作品', '浏览作品，选择后查看详情。'],
  发现: ['CATEGORIES', '分类浏览', '按示例分类筛选作品。'],
  动态: ['FOLLOWING', '关注动态', '动态列表布局示例，使用本地作品数据。'],
  收藏: ['SAVED ARTWORKS', '我的收藏', '仅显示本次预览中收藏的作品。'],
};

function toast(message) {
  $('#toast').textContent = message;
  $('#toast').classList.add('visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $('#toast').classList.remove('visible'), 2600);
}
function filtered() {
  let items = artworks.filter(a => (state.tag === '全部' || a.tag === state.tag)
    && (!state.query || `${a.title} ${a.tag} ${a.id}`.includes(state.query))
    && (state.route !== '收藏' || state.saved.has(a.id)));
  if (state.tab === '热门' || state.route === '动态') items = [...items].reverse();
  return items;
}
function anchor() {
  if (!$('#feed').getClientRects().length) return null;
  const top = $('#feed').getBoundingClientRect().top;
  const card = [...document.querySelectorAll('.card')].find(v => v.getBoundingClientRect().bottom > top);
  return card ? {id:card.dataset.id, offset:card.getBoundingClientRect().top - top} : null;
}
function restoreAnchor(position) {
  if (!position || !$('#feed').getClientRects().length) return;
  const card = document.querySelector(`.card[data-id="${position.id}"]`);
  if (card) $('#feed').scrollTop += card.getBoundingClientRect().top - $('#feed').getBoundingClientRect().top - position.offset;
}
function render() {
  const items = filtered();
  const unavailable = TabletPreview.status($('#preview-state'));
  $('#count').textContent = `${items.length} 幅作品 · 示例`;
  $('#gallery').hidden = unavailable;
  $('#empty').hidden = unavailable || items.length > 0;
  $('#empty h2').textContent = state.route === '收藏' && !state.saved.size ? '暂无收藏' : '暂无匹配作品';
  $('#empty p').textContent = state.route === '收藏' && !state.saved.size ? '可在作品卡片或详情中收藏作品。' : '请调整关键词或分类。';
  $('#gallery').innerHTML = items.map(a => `<article class="card ${state.selected === a.id ? 'selected' : ''}" data-id="${a.id}">
    <button class="art-open" data-open="${a.id}" aria-label="查看 ${a.title}" aria-pressed="${state.selected === a.id}"><img src="${a.src}" alt="${a.title}" loading="lazy"></button>
    <div class="card-meta"><div class="meta-text"><div class="card-title">${a.title}</div><div class="card-id">${a.tag} · 示例分类</div></div>
    <button class="like ${state.saved.has(a.id) ? 'saved' : ''}" data-like="${a.id}" aria-label="${state.saved.has(a.id) ? '取消收藏' : '收藏'} ${a.title}" aria-pressed="${state.saved.has(a.id)}">${icon('heart')}</button></div></article>`).join('');
  document.querySelectorAll('[data-tag],[data-tab]').forEach(button => {
    const active = button.dataset.tag ? state.tag === button.dataset.tag : state.tab === button.dataset.tab;
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', active);
  });
}
function selected() { return artworks.find(a => a.id === state.selected); }
function like(id) {
  const position = anchor();
  const fromDetail = document.activeElement?.id === 'detail-like';
  state.saved.has(id) ? state.saved.delete(id) : state.saved.add(id);
  render();
  restoreAnchor(position);
  if (state.selected === id) {
    const scroll = $('#detail').scrollTop;
    renderDetail();
    $('#detail').scrollTop = scroll;
  }
  const focus = fromDetail ? $('#detail-like') : document.querySelector(`[data-like="${id}"]`);
  (focus || $('#clear-filters')).focus({preventScroll:true});
  toast(state.saved.has(id) ? '已收藏（演示）' : '已取消收藏');
}
function openDetail(id) {
  if (!artworks.some(a => a.id === id)) return;
  const position = anchor();
  if (position) positions.set(state.route, position);
  if (!state.selected) detailOrigin = id;
  state.selected = id;
  $('#detail').hidden = false;
  $('#content').classList.add('has-detail');
  renderDetail();
  render();
  restoreAnchor(position);
  $('#detail').scrollTop = 0;
  $('#close-detail').focus({preventScroll:true});
}
function closeDetail(focus = true) {
  if (!state.selected) return;
  state.selected = null;
  $('#detail').hidden = true;
  $('#content').classList.remove('has-detail');
  render();
  restoreAnchor(positions.get(state.route));
  if (focus) (document.querySelector(`[data-open="${detailOrigin}"]`) || $('#search')).focus({preventScroll:true});
}
function renderDetail() {
  const a = selected();
  if (!a) return;
  const related = artworks.filter(b => b.id !== a.id && b.tag === a.tag).slice(0,3);
  $('#detail').innerHTML = `<div class="detail-head"><span>作品详情</span><button class="icon" id="expand-art" aria-label="全屏查看">${icon('expand')}</button><button class="icon" id="close-detail" aria-label="关闭作品详情">${icon('close')}</button></div>
    <div class="detail-art"><button id="large-art" aria-label="全屏查看作品"><img src="${a.src}" alt="${a.title}"></button></div>
    <div class="detail-body"><div class="detail-label">ARTWORK DETAILS</div><h2>${a.title}</h2><div class="detail-id">ID ${a.id}</div>
    <div class="author">原作与作者<small>通过下方 Pixiv 链接查看完整资料。</small></div>
    <div class="detail-tags"><span>${a.tag} · 示例分类</span></div><p class="description">当前显示本地预览图。收藏与下载仅在本次预览中模拟。</p>
    <div class="detail-actions"><button class="primary" id="detail-like" aria-pressed="${state.saved.has(a.id)}">${icon('heart')}${state.saved.has(a.id) ? '已收藏' : '收藏作品'}</button><button id="download-art">${icon('download')}模拟下载</button></div>
    <a class="source-link" target="_blank" rel="noopener noreferrer" href="https://www.pixiv.net/artworks/${a.id}">查看原作与作者 ↗</a>
    <div class="related-label">同类作品</div><div class="related">${related.map(b => `<button data-open="${b.id}" aria-label="查看 ${b.title}"><img src="${b.src}" alt="${b.title}" loading="lazy"></button>`).join('')}</div></div>`;
  $('#close-detail').onclick = () => closeDetail();
  $('#expand-art').onclick = openViewer;
  $('#large-art').onclick = openViewer;
  $('#detail-like').onclick = () => like(a.id);
  $('#download-art').onclick = () => {
    const exists = state.queue.includes(a.id);
    if (!exists) state.queue.push(a.id);
    toast(exists ? '已在模拟队列中' : '已加入模拟下载队列');
  };
}
function openViewer() {
  if (!selected()) return;
  viewerOrigin = document.activeElement.id;
  sequence = filtered().map(a => a.id);
  if (!sequence.includes(state.selected)) sequence = artworks.map(a => a.id);
  renderViewer();
  $('#viewer').showModal();
}
function renderViewer() {
  const a = selected();
  $('#viewer-image').src = a.src;
  $('#viewer-image').alt = a.title;
  $('#viewer-title').textContent = `${a.title} · 预览图`;
  $('#viewer-index').textContent = `${sequence.indexOf(a.id)+1} / ${sequence.length}`;
  $('.viewer-canvas').classList.remove('zoomed');
  $('#zoom').textContent = '放大';
  $('#zoom').setAttribute('aria-pressed', false);
}
function stepViewer(delta) {
  state.selected = sequence[(sequence.indexOf(state.selected) + delta + sequence.length) % sequence.length];
  renderViewer();
  renderDetail();
  render();
}
function info(html) {
  infoOrigin = document.activeElement;
  $('#info-body').innerHTML = html;
  $('#info').showModal();
}
function settings() {
  info(`<div class="eyebrow">PREVIEW SETTINGS</div><h2>预览设置</h2><p>画廊布局与交互示例，未连接账号或业务接口。</p><p>/ 搜索 · Esc 返回 · ← → 全屏切图</p>${TabletPreview.controls()}<p><a href="magazine.html">查看发现页原型 ↗</a></p>`);
}
function route(value) {
  const position = anchor();
  if (position) positions.set(state.route, position);
  closeDetail(false);
  state.route = value;
  document.querySelectorAll('[data-route]').forEach(button => {
    const active = button.dataset.route === value;
    button.classList.toggle('active', active);
    if (active) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
  });
  const [eyebrow,title,description] = headings[value];
  $('#eyebrow').textContent = eyebrow;
  $('#page-title').textContent = title;
  $('#subtitle').textContent = description;
  state.query = ''; state.tag = '全部'; state.tab = '全部'; $('#search').value = '';
  render();
  $('#feed').scrollTop = 0;
  restoreAnchor(positions.get(value));
}
document.addEventListener('click', event => {
  const open = event.target.closest('[data-open]');
  if (open) openDetail(open.dataset.open);
  const save = event.target.closest('[data-like]');
  if (save) like(save.dataset.like);
});
document.querySelectorAll('[data-route]').forEach(button => button.onclick = () => route(button.dataset.route));
document.querySelectorAll('[data-tag],[data-tab]').forEach(button => button.onclick = () => {
  if (button.dataset.tag) state.tag = button.dataset.tag; else state.tab = button.dataset.tab;
  render();
  if (button.dataset.tab === '热门') toast('热门排序为示例');
});
$('#search').oninput = event => { state.query = event.target.value.trim(); render(); };
$('#clear-filters').onclick = () => route('推荐');
$('#density').onchange = event => {
  const position = anchor();
  document.documentElement.style.setProperty('--card', {normal:'198px', compact:'152px', large:'260px'}[event.target.value]);
  $('#gallery').classList.toggle('large', event.target.value === 'large');
  restoreAnchor(position);
};
$('#theme').onclick = TabletPreview.toggleTheme;
$('#preview-settings').onclick = settings;
$('#account').onclick = settings;
$('#downloads').onclick = () => info(`<div class="eyebrow">DOWNLOADS</div><h2>下载队列</h2><p>${state.queue.length ? state.queue.map(id => `作品 ${id} · 已加入模拟队列`).join('<br>') : '暂无下载任务。可在作品详情中添加模拟下载。'}</p><p>此处仅演示队列，不下载文件。</p>`);
$('#close-info').onclick = () => $('#info').close();
$('#info').addEventListener('close', () => infoOrigin?.focus({preventScroll:true}));
$('#exit-viewer').onclick = () => $('#viewer').close();
$('#viewer').addEventListener('close', () => (document.getElementById(viewerOrigin) || $('#close-detail'))?.focus({preventScroll:true}));
$('#prev').onclick = () => stepViewer(-1);
$('#next').onclick = () => stepViewer(1);
$('#zoom').onclick = () => {
  const zoomed = $('.viewer-canvas').classList.toggle('zoomed');
  $('#zoom').textContent = zoomed ? '适应窗口' : '放大';
  $('#zoom').setAttribute('aria-pressed', zoomed);
};
document.addEventListener('previewstate', () => { closeDetail(false); render(); });
document.addEventListener('keydown', event => {
  if (event.target.matches('input,select,textarea')) return;
  const viewing = $('#viewer').open, informing = $('#info').open;
  if (event.key === '/' && !viewing && !informing) { event.preventDefault(); $('#search').focus(); }
  if (event.key === 'Escape' && !viewing && !informing && state.selected) { event.preventDefault(); closeDetail(); }
  if (viewing && ['ArrowLeft','ArrowRight'].includes(event.key)) { event.preventDefault(); stepViewer(event.key === 'ArrowLeft' ? -1 : 1); }
});
render();
