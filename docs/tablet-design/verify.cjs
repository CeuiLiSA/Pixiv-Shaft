/* Run against a server rooted at the repository. See README.md. */
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const path = require('node:path');
const base = process.env.TABLET_PREVIEW_URL || 'http://127.0.0.1:8766/docs/tablet-design/';
(async () => {
  const browser = await chromium.launch({channel:'chrome', headless:true});
  const errors = [];
  try {
    for (const name of ['index.html','magazine.html']) {
      const page = await browser.newPage({viewport:{width:1440,height:1000}, reducedMotion:'reduce'});
      page.on('pageerror', e => errors.push(`${name}: ${e.message}`));
      await page.goto(base + name);
      await page.evaluate(() => document.fonts.ready);
      assert.ok(await page.evaluate(() => document.fonts.check('600 16px Montserrat')));
      const gallery = name === 'index.html';
      const capture = async file => page.screenshot({path:path.join(__dirname,file), fullPage:!gallery});
      const overflow = async label => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1),false,`${name}: ${label}`);
      for (const width of [320,390,600,768,960,1039,1040,1280,1440,1920]) {
        await page.setViewportSize({width,height:1000});
        await overflow(`overflow at ${width}`);
      }
      await page.setViewportSize({width:1440,height:1000});
      await capture(gallery ? 'gallery.png' : 'magazine.png');
      for (const theme of ['light','dark']) for (const accent of ['violet','rose','teal']) {
        await page.evaluate(({theme,accent}) => { document.documentElement.dataset.theme=theme; document.documentElement.dataset.accent=accent; },{theme,accent});
        await overflow(`${theme}/${accent}`);
      }
      await page.evaluate(() => { document.documentElement.dataset.accent='violet'; });
      await capture(gallery ? 'dark.png' : 'magazine-dark.png');
      await page.evaluate(() => { document.documentElement.dataset.theme='light'; });
      await page.setViewportSize({width:800,height:1100});
      await capture(gallery ? 'portrait.png' : 'magazine-portrait.png');
      await page.setViewportSize({width:390,height:844});
      await page.screenshot({path:path.join(__dirname,gallery ? 'mobile.png' : 'magazine-mobile.png')});
      await page.setViewportSize({width:1440,height:1000});
      if (gallery) {
        await page.locator('[data-tag="风景"]').click();
        const count = await page.locator('.card').count();
        assert.ok(count > 0 && count < 20);
        await page.locator('[data-open]').first().click();
        await capture('detail.png');
        assert.equal(await page.locator('#feed').isVisible(),true);
        await page.setViewportSize({width:1440,height:420});
        assert.equal(await page.locator('#feed').isVisible(),false);
        await page.setViewportSize({width:1440,height:1000});
        const title = await page.locator('.detail-body h2').textContent();
        await page.setViewportSize({width:800,height:1100});
        assert.equal(await page.locator('#feed').isVisible(),false);
        assert.equal(await page.locator('.detail-body h2').textContent(),title);
        await page.setViewportSize({width:1440,height:1000});
        await page.locator('#detail-like').click();
        assert.equal(await page.locator('#detail-like').getAttribute('aria-pressed'),'true');
        assert.equal(await page.locator('.like.saved').count(),1);
        await page.locator('#download-art').click();
        await page.locator('#download-art').click();
        await page.locator('#expand-art').click();
        await page.keyboard.press('ArrowRight');
        assert.equal(await page.locator('#viewer-index').textContent(),`2 / ${count}`);
        await page.locator('#zoom').click();
        assert.equal(await page.locator('.viewer-canvas').getAttribute('class'),'viewer-canvas zoomed');
        await page.keyboard.press('Escape');
        assert.equal(await page.locator('#viewer').isVisible(),false);
        assert.equal(await page.locator('#detail').isVisible(),true);
        await page.keyboard.press('Escape');
        assert.equal(await page.locator('#detail').isVisible(),false);
        assert.equal(await page.evaluate(() => document.activeElement.hasAttribute('data-open')),true);
        await page.locator('#downloads').click();
        assert.equal((await page.locator('#info-body').textContent()).match(/已加入模拟队列/g).length,1);
        await page.keyboard.press('Escape');
        await page.locator('[data-route="收藏"]').click();
        assert.equal(await page.locator('.card').count(),1);
        await page.locator('[data-like]').click();
        assert.equal(await page.locator('#empty').isVisible(),true);
        await page.locator('#clear-filters').click();
        await page.locator('#search').fill('no-matching-artwork');
        assert.equal(await page.locator('#empty').isVisible(),true);
        await page.locator('#search').fill('');
        const before = await page.locator('.art-open').first().boundingBox();
        await page.locator('#density').selectOption('large');
        assert.ok((await page.locator('.art-open').first().boundingBox()).width > before.width);
        await page.locator('#preview-settings').click();
      } else {
        await page.locator('.solid-button[data-collection]').click();
        assert.ok(await page.locator('.work').count() > 0);
        await page.locator('#collection-grid [data-art]').first().click();
        await page.locator('#save-art').click();
        assert.equal(await page.locator('#save-art').getAttribute('aria-pressed'),'true');
        await page.keyboard.press('ArrowRight');
        assert.match(await page.locator('#position').textContent(),/^2 \/ /);
        await page.locator('#zoom').click();
        await page.keyboard.press('Escape');
        assert.equal(await page.locator('#art-dialog').isVisible(),false);
        await page.locator('[data-page="saved"]').click();
        assert.equal(await page.locator('#collection-grid .work').count(),1);
        await page.locator('#collection-grid [data-save]').click();
        assert.equal(await page.locator('#empty').isVisible(),true);
        await page.locator('#clear-filters').click();
        await page.locator('#query').fill('no-matching-artwork');
        assert.equal(await page.locator('#empty').isVisible(),true);
        await page.locator('#query').fill('');
        await page.locator('#about').click();
      }
      await page.locator('[data-preview-theme="dark"]').click();
      await page.locator('[data-preview-accent="teal"]').click();
      await page.locator('[data-preview-state="error"]').click();
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('#preview-state').isVisible(),true);
      await page.screenshot({path:path.join(__dirname,gallery ? 'error.png' : 'magazine-error.png')});
      await page.locator('[data-preview-retry]').click();
      assert.equal(await page.locator('#preview-state').isVisible(),false);
      await page.evaluate(() => TabletPreview.setState('loading'));
      assert.equal(await page.locator('#preview-state').isVisible(),true);
      await page.locator('[data-preview-retry]').click();
      await page.evaluate(() => { document.documentElement.dataset.theme='light';document.documentElement.dataset.accent='violet'; });
      // Double actual text sizes, including fixed-pixel labels; do not claim root rem changes test zoom.
      await page.setViewportSize({width:800,height:1100});
      await page.evaluate(() => {
        const sizes = [...document.querySelectorAll('body *')].filter(e => e.matches('input,select,textarea') || [...e.childNodes].some(n=>n.nodeType===3&&n.textContent.trim())).map(e => [e,parseFloat(getComputedStyle(e).fontSize)]);
        sizes.forEach(([e,size])=>e.style.fontSize=`${size*2}px`);
      });
      for (const width of [320,390,800,1440]) {
        await page.setViewportSize({width,height:1100});
        await overflow(`200% text at ${width}`);
      }
      await page.setViewportSize({width:800,height:1100});
      await page.evaluate(() => { const toast=document.querySelector('.toast, #notice');toast?.classList.remove('visible'); });
      await capture(gallery ? 'large-text.png' : 'magazine-large-text.png');
      await page.evaluate(() => { const heading=document.querySelector('#page-title, #collection-title');heading.textContent='长标题布局检查：作品列表与分类说明，文本换行后仍可查看内容与操作'; });
      await overflow('long heading');
      const broken = await page.evaluate(() => [...document.images].filter(img=>img.complete&&!img.naturalWidth).map(img=>img.src));
      assert.deepEqual(broken,[],`${name}: broken images`);
      await page.close();
    }
    assert.deepEqual(errors,[]);
    console.log('PASS: both pages; 10 widths; 6 themes; 200% text; filtering, empty states, synchronized saved state, list/detail resize, viewer keyboard and focus, deduplicated download demo, loading/error/retry; images and JS valid.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
