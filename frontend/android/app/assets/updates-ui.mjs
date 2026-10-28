// A native dialog, never a remote-code or web-assets update.
function add(){const h=document.querySelector('#app header');if(!h||h.querySelector('[data-updates]')||!window.JudgeAndroid?.openUpdates)return;const b=document.createElement('button');b.textContent='Обновления приложения';b.dataset.updates='';b.style.cssText='font-size:12px;min-height:34px;padding:6px 10px;margin-top:8px';b.onclick=()=>window.JudgeAndroid.openUpdates();h.append(b);}
new MutationObserver(add).observe(document.querySelector('#app'),{childList:true,subtree:true});add();
