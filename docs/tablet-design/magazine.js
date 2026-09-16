'use strict';
const q = selector => document.querySelector(selector);
const icon = name => `<svg aria-hidden="true"><use href="#${name}"/></svg>`;
const items = artworks.map(a => a.id === '13534647' ? {...a, src:'magazine-assets/dusk.jpg'} : {...a});
const state = {page:'journal', filter:'全部', query:'', saved:new Set(), selected:null, sequence:[], dailyOffset:0};
let noticeTimer;
let lastFocus;

function notice(message) {
  q('#notice').textContent = message;
  q('#notice').classList.add('visible');
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => q('#notice').classList.remove('visible'), 2200);
}
function card(a) {
  const saved = state.saved.has(a.id);
  return `<article class="work"><button class="work-open" data-art="${a.id}" aria-label="查看 ${a.title}"><img src="${a.src}" alt="${a.title}，示例作品 ${a.id}" loading="lazy"></button><div class="work-meta"><div><h3>${a.title}</h3><p>${a.tag} / ${a.id}</p></div><button class="save" data-save="${a.id}" aria-label="收藏 ${a.title}" aria-pressed="${saved}">${icon('heart')}</button></div></article>`;
}
function renderDaily() {
  q('#daily').innerHTML = Array.from({length:4}, (_,i) => items[(i+state.dailyOffset+6)%items.length]).map(card).join('');
}
const chapters = [
  {title:'风景作品', tag:'风景', text:'查看场景与环境创作。', indices:[2,7]},
  {title:'人物作品', tag:'人物', text:'查看人物分类中的作品。', indices:[1,6]},
  {title:'幻想作品', tag:'幻想', text:'查看幻想题材作品。', indices:[9,4]}
];
q('#stories').innerHTML = chapters.map((c,i) => `<article class="story"><button class="story-images" data-collection="${c.tag}" aria-label="浏览专题 ${c.title}">${c.indices.map(index=>`<img src="${items[index].src}" alt="${items[index].title}" loading="lazy">`).join('')}</button><div class="story-meta"><div><h3>${c.title}</h3><p>${c.text}</p></div><span>0${i+1}</span></div></article>`).join('');

function filtered() {
  return items.filter(a => (state.page !== 'saved' || state.saved.has(a.id)) && (state.filter === '全部' || a.tag === state.filter) && (!state.query || `${a.title} ${a.tag} ${a.id}`.includes(state.query)));
}
function renderCollection() {
  const list = filtered();
  q('#collection-grid').innerHTML = list.map(card).join('');
  q('#result-count').textContent = `${list.length} 幅作品 · 示例`;
  const unavailable = TabletPreview.status(q('#preview-state'));
  q('#collection-grid').hidden = unavailable;
  q('#empty').hidden = unavailable || list.length > 0;
  q('#empty-text').textContent = state.page==='saved' && !state.saved.size ? '可在作品卡片或详情中收藏作品。' : '请调整关键词或分类。';
  q('#collection-title').textContent = state.query ? `搜索：${state.query}` : state.page==='saved' ? '我的收藏' : ({全部:'全部作品',风景:'风景作品',人物:'人物作品',幻想:'幻想作品'}[state.filter]);
  q('#collection-description').textContent = state.page==='saved' ? '仅显示本次预览中收藏的作品。' : '分类为本地示例，原作信息见 Pixiv 链接。';
  document.querySelectorAll('[data-filter]').forEach(b => {
    b.classList.toggle('active',b.dataset.filter===state.filter);
    b.setAttribute('aria-pressed',b.dataset.filter===state.filter);
  });
}
function showPage(page, filter='全部', keepQuery=false) {
  state.page = page;
  state.filter = filter;
  if (!keepQuery) {state.query=''; q('#query').value='';}
  q('#journal').hidden = page!=='journal';
  q('#collection').hidden = page==='journal';
  document.querySelectorAll('.nav-item').forEach(b => {
    b.classList.toggle('active',b.dataset.page===page);
    if (b.dataset.page===page) b.setAttribute('aria-current','page'); else b.removeAttribute('aria-current');
  });
  if (page!=='journal') renderCollection();
  window.scrollTo({top:0,behavior:'instant'});
}
function toggleSave(id) {
  state.saved.has(id) ? state.saved.delete(id) : state.saved.add(id);
  document.querySelectorAll(`[data-save="${id}"]`).forEach(b => {
    b.innerHTML = icon('heart');
    b.setAttribute('aria-label', `${state.saved.has(id)?'取消收藏':'收藏'} 作品 ${id}`);
    b.setAttribute('aria-pressed',state.saved.has(id));
  });
  if (q('#art-dialog').open && state.selected===id) updateSaveButton();
  if (state.page==='saved') {
    const focused = document.activeElement?.dataset.save === id;
    renderCollection();
    if (focused) (q('[data-save]') || q('#clear-filters')).focus({preventScroll:true});
  }
  notice(state.saved.has(id)?'已收藏（演示）':'已取消收藏');
}
function updateSaveButton() {
  q('#save-art').innerHTML = icon('heart') + (state.saved.has(state.selected)?'已收藏':'收藏作品');
  q('#save-art').setAttribute('aria-pressed',state.saved.has(state.selected));
}
function renderArt() {
  const a = items.find(a=>a.id===state.selected);
  q('#art-image').src = a.src;
  q('#art-image').alt = a.title;
  q('#art-title').textContent = a.title;
  q('#art-id').textContent = `ILLUST / ${a.id}`;
  q('#art-tags').textContent = `${a.tag} · 示例分类`;
  q('#source').href = `https://www.pixiv.net/artworks/${a.id}`;
  q('#position').textContent = `${state.sequence.indexOf(a.id)+1} / ${state.sequence.length}`;
  q('#art-canvas').classList.remove('zoomed');
  q('#zoom').textContent = '放大';
  q('.art-info').scrollTop = 0;
  updateSaveButton();
}
function openArt(id) {
  lastFocus = document.activeElement;
  state.sequence = (state.page==='journal'?items:filtered()).map(a=>a.id);
  if (!state.sequence.includes(id)) state.sequence = items.map(a=>a.id);
  state.selected = id;
  renderArt();
  q('#art-dialog').showModal();
  document.body.style.overflow = 'hidden';
}
function step(delta) {
  state.selected = state.sequence[(state.sequence.indexOf(state.selected)+delta+state.sequence.length)%state.sequence.length];
  renderArt();
}
document.addEventListener('click', e => {
  const art=e.target.closest('[data-art]'); if(art) openArt(art.dataset.art);
  const save=e.target.closest('[data-save]'); if(save) toggleSave(save.dataset.save);
  const collection=e.target.closest('[data-collection]'); if(collection) showPage('works',collection.dataset.collection);
  const page=e.target.closest('[data-page]'); if(page) showPage(page.dataset.page);
  const filter=e.target.closest('[data-filter]'); if(filter){state.filter=filter.dataset.filter;renderCollection();}
});
q('#clear-filters').onclick=()=>showPage('works');
document.addEventListener('previewstate',()=>showPage('works',state.filter,true));
q('#back-journal').onclick=()=>showPage('journal');
q('#query').oninput=e=>{state.query=e.target.value.trim();showPage(state.page==='saved'?'saved':'works',state.filter,true);};
q('#shuffle').onclick=()=>{state.dailyOffset=(state.dailyOffset+4)%items.length;renderDaily();};
q('#random-art').onclick=()=>openArt(items[Math.floor(Math.random()*items.length)].id);
q('#theme').onclick=TabletPreview.toggleTheme;
q('#about').onclick=()=>{q('#preview-controls').innerHTML=TabletPreview.controls();q('#about-dialog').showModal();};
q('#close-about').onclick=()=>q('#about-dialog').close();
q('#close-art').onclick=()=>q('#art-dialog').close();
q('#art-dialog').addEventListener('close',()=>{document.body.style.overflow='';if (lastFocus?.isConnected && lastFocus.getClientRects().length) lastFocus.focus({preventScroll:true}); else (q('[data-art]')?.getClientRects().length ? q('[data-art]') : q('#back-journal')).focus({preventScroll:true});});
q('#previous').onclick=()=>step(-1);
q('#next').onclick=()=>step(1);
q('#zoom').onclick=()=>{q('#art-canvas').classList.toggle('zoomed');q('#zoom').textContent=q('#art-canvas').classList.contains('zoomed')?'适应窗口':'放大';};
q('#save-art').onclick=()=>toggleSave(state.selected);
q('#more-like-this').onclick=()=>{const tag=items.find(a=>a.id===state.selected).tag;q('#art-dialog').close();showPage('works',tag);q('#back-journal').focus({preventScroll:true});};
document.addEventListener('keydown',e=>{
  if(e.target.matches('input,textarea,select'))return;
  if(e.key==='/'&&!q('#art-dialog').open&&!q('#about-dialog').open){e.preventDefault();q('#query').focus();}
  if(q('#art-dialog').open&&(e.key==='ArrowLeft'||e.key==='ArrowRight')){e.preventDefault();step(e.key==='ArrowLeft'?-1:1);}
});
renderDaily();
