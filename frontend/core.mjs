export const emptyResult=(competitor_id,route_id)=>({competitor_id,route_id,total_attempt:0,zone_attempt:0,top_attempt:0});
export function validate(r){
 for(const k of ['competitor_id','route_id'])if(!Number.isSafeInteger(r[k])||r[k]<1)throw Error('Некорректный идентификатор');
 for(const k of ['total_attempt','zone_attempt','top_attempt'])if(!Number.isSafeInteger(r[k])||r[k]<0)throw Error('Попытки должны быть целыми и неотрицательными');
 if(r.zone_attempt>r.total_attempt||r.top_attempt>r.total_attempt)throw Error('Зона и топ не могут быть позже общего числа попыток');
 if(r.top_attempt&&r.zone_attempt>r.top_attempt)throw Error('Зона не может быть позже топа');
 return r;
}
export function applyAction(previous,action){
 const r={...validate(previous)};if(r.top_attempt)throw Error('Трасса завершена. Используйте исправление');
 if(!['fall','zone','top'].includes(action))throw Error('Неизвестное действие');
 if(action==='top'&&!r.zone_attempt)throw Error('Топ без ранее взятой зоны: правило не согласовано. Используйте исправление с фактическими значениями');
 r.total_attempt++;
 if(action==='zone'&&!r.zone_attempt)r.zone_attempt=r.total_attempt;
 if(action==='top')r.top_attempt=r.total_attempt;
 return validate(r);
}
export const keyOf=r=>`${r.route_id}:${r.competitor_id}`;
export function assertResponse(want,got){validate(got);for(const k of Object.keys(emptyResult(1,1)))if(got[k]!==want[k])throw Error('Сервер вернул другой результат. Нужна сверка');if(!Number.isSafeInteger(got.id)||got.id<1)throw Error('Нет id сохранённого результата');return got;}
export class Api{
 constructor(base='/api'){this.base=base;}
 async request(path,options={}){const c=new AbortController(),t=setTimeout(()=>c.abort(),12000);try{const r=await fetch(this.base+path,{...options,signal:c.signal,headers:{'Content-Type':'application/json',...options.headers}});if(!r.ok)throw Error(`API: HTTP ${r.status}`);return await r.json();}finally{clearTimeout(t);}}
 async list(path){let rows=[];for(let skip=0;skip<10000;skip+=100){const batch=await this.request(`${path}?skip=${skip}&limit=100`);if(!Array.isArray(batch))throw Error('Неверный ответ списка');rows.push(...batch);if(batch.length<100)return rows;}throw Error('Слишком большой список');}
 async save(r){validate(r);return assertResponse(r,await this.request('/results/',{method:'POST',body:JSON.stringify(r)}));}
}
