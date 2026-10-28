import puppeteer from 'puppeteer-core';

const base = 'http://127.0.0.1:8781/';
let failures = 0;
const check = (name, ok, detail='') => { console.log((ok?'PASS ':'FAIL ')+name+(ok?'':' :: '+detail)); if(!ok) failures++; };
const sleep = ms => new Promise(r=>setTimeout(r,ms));

const browser = await puppeteer.launch({executablePath:'/usr/bin/google-chrome', headless:'new',
  args:['--no-sandbox','--disable-dev-shm-usage']});
const page = await browser.newPage();
await page.setViewport({width:390, height:844, isMobile:true, hasTouch:true, deviceScaleFactor:2});
const consoleErrors = [], failedReqs = [];
page.on('console', m => { if (m.type()==='error') consoleErrors.push(m.text()); });
page.on('requestfailed', r => failedReqs.push(r.url()+' '+(r.failure()?.errorText||'')));
page.on('response', r => { if (r.status()>=400) failedReqs.push(r.url()+' HTTP '+r.status()); });

const click = sel => page.evaluate(s => { const el = document.querySelector(s); if (!el) throw new Error('missing '+s); el.click(); }, sel);
const check2 = (i, on) => page.evaluate(({i,on}) => { const els=[...document.querySelectorAll('[data-pick]')]; if(!els[i]) throw new Error('no pick '+i); if(els[i].checked!==on) els[i].click(); }, {i,on});

await page.goto(base, {waitUntil:'networkidle0'});
await page.screenshot({path:'build/shots/apk-01-landing.png'});
check('landing renders', await page.$eval('h1', e=>e.textContent)==='Турнир');

await click('#training'); await sleep(250);
await check2(0,true); await sleep(150); await check2(1,true); await sleep(150);
await click('#confirm'); await sleep(250);
const rows = await page.$$eval('[data-open]', els=>els.length);
check('six demo athletes listed', rows===6, 'rows='+rows);

await click('[data-open="1"]'); await sleep(300);
await page.screenshot({path:'build/shots/apk-02-quick-sheet.png'});
await click('[data-action="top"]'); await sleep(350);
const row1 = await page.$eval('[data-open="1"] small', e=>e.textContent);
check('top without zone fills zone same attempt', /Попыток 1 · зона 1 · топ 1/.test(row1), row1);
const stored = await page.evaluate(()=>JSON.parse(localStorage.getItem('bouldering_training_v3')));
const r1 = stored?.results?.['1:1'];
check('localStorage persisted zone+top', r1 && r1.zone_attempt===1 && r1.top_attempt===1 && r1.total_attempt===1, JSON.stringify(r1));

await page.reload({waitUntil:'networkidle0'});
await click('#training'); await sleep(300);
const row1b = await page.$eval('[data-open="1"] small', e=>e.textContent).catch(()=> '');
check('result survives reload', /Попыток 1 · зона 1 · топ 1/.test(row1b), row1b);
await page.screenshot({path:'build/shots/apk-03-after-reload.png'});

await click('[data-open="2"]'); await sleep(250);
await click('[data-action="fall"]'); await sleep(250);
await click('[data-open="2"]'); await sleep(250);
await click('[data-action="top"]'); await sleep(350);
const row2 = await page.$eval('[data-open="2"] small', e=>e.textContent);
check('fall then top gives 2/2/2', /Попыток 2 · зона 2 · топ 2/.test(row2), row2);

await page.evaluate(()=>localStorage.clear());
await click('[data-tab="profile"]'); await sleep(200);
await click('#exit'); await sleep(200);
await click('#live');
await sleep(2500);
const liveError = await page.$eval('.error', e=>e.textContent).catch(()=>'');
const liveBody = await page.$eval('main', e=>e.textContent);
check('live mode loaded without error', !/Не удалось загрузить API/.test(liveError), liveError);
check('live mode shows routes picker state', /пустой список трасс|Мои трассы/.test(liveBody), liveBody.slice(0,200));
await page.screenshot({path:'build/shots/apk-04-live.png'});

check('no console errors', consoleErrors.length===0, consoleErrors.join(' | '));
check('no failed/4xx-5xx requests', failedReqs.length===0, failedReqs.join(' | '));

await browser.close();
console.log(failures===0 ? 'ALL UI TESTS PASSED' : failures+' UI FAILURES');
process.exit(failures===0?0:1);
